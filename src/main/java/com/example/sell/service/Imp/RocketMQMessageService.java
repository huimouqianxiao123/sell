package com.example.sell.service.Imp;

import cn.hutool.core.lang.UUID;
import com.example.sell.dao.SeckillMessageMapper;
import com.example.sell.domain.pojo.SeckillMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * @author 屈轩
 */
@Slf4j
@Component
public class RocketMQMessageService {

    private static final String SECKILL_TOPIC = "seckill-topic";

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private SeckillIdempotentService seckillIdempotentService;

    @Resource
    private SeckillMessageMapper seckillMessageMapper;

    @Resource(name = "ioTaskExecutor")
    private ThreadPoolTaskExecutor ioTaskExecutor;

    /**
     * 可靠发送秒杀消息
     * <p>
     * 保障机制：
     * 1. Redis 幂等检查（防止重复发送，速度快）
     * 2. 同步持久化到 MySQL 本地消息表（确保补偿任务有据可查）
     * 3. 异步发送 MQ，成功/失败回调更新 MySQL 状态
     * 4. 定时补偿任务兜底：扫描 MySQL 中发送失败/超时未消费的消息进行重发
     * </p>
     *
     * @param userId           用户 ID
     * @param seckillProductId 秒杀商品 ID
     * @return 消息 ID，用于后续追踪
     */
    public String reliableSend(Long userId, Long seckillProductId) {
        // 0. 业务幂等检查：同一用户同一秒杀商品已有待处理/已发送消息时不重复创建
        int pendingCount = seckillMessageMapper.countPendingMessage(userId, seckillProductId);
        if (pendingCount > 0) {
            log.info("【可靠消息】检测到已有待处理消息，跳过重复创建，用户 ID: {}, 商品 ID: {}", userId, seckillProductId);
            return null;
        }

        // 1. 先生成消息 ID（避免幂等检查时传入 null）
        String messageId = UUID.randomUUID().toString().replace("-", "");

        // 2. Redis 幂等检查（以 messageId 为键，允许同一用户对同一商品失败后重试）
        if (!seckillIdempotentService.checkAndSetMessageSent(messageId)) {
            log.info("【可靠消息】消息已存在，跳过重复发送，消息 ID: {}", messageId);
            return null;
        }

        // 3. 生成消息内容
        String messageContent = messageId + ":" + userId + ":" + seckillProductId;

        // 4. Redis 缓存消息（快速，用于短期幂等和消费者快速查询）
        seckillIdempotentService.saveMessage(messageId, userId, seckillProductId, messageContent);

        // 5. 同步持久化到 MySQL 本地消息表
        //    确保 MySQL 中有记录后再发 MQ，这样即使 MQ 发送失败，补偿任务也能兜底
        //    若 MySQL 持久化失败，会抛出异常，调用方可据此回滚 Redis 库存
        try {
            SeckillMessage seckillMessage = SeckillMessage.builder()
                    .messageId(messageId)
                    .userId(userId)
                    .seckillProductId(seckillProductId)
                    .messageContent(messageContent)
                    .status(SeckillMessage.STATUS_PENDING)
                    .consumeStatus(SeckillMessage.CONSUME_STATUS_PENDING)
                    .retryCount(0)
                    .consumeRetryCount(0)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            seckillMessageMapper.insert(seckillMessage);
            log.debug("【可靠消息】消息已同步持久化到 MySQL，消息 ID: {}", messageId);
        } catch (Exception e) {
            // MySQL 持久化失败 → 清理 Redis 幂等标记，允许调用方回滚 Redis 库存后用户重试
            log.error("【可靠消息】MySQL 持久化失败，清理幂等标记，消息 ID: {}", messageId, e);
            seckillIdempotentService.removeMessageSent(messageId);
            throw new RuntimeException("消息持久化失败，需要回滚 Redis 库存", e);
        }

        // 6. 异步发送 MQ，回调中更新 MySQL 状态
        //    此时 MySQL 已有记录，即使 MQ 发送失败，补偿任务也能扫描到并重发
        rocketMQTemplate.asyncSend(SECKILL_TOPIC, messageContent, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.debug("【可靠消息】MQ 发送成功，消息 ID: {}, MQ msgId: {}", messageId, sendResult.getMsgId());
                // 异步更新 MySQL 发送状态为成功
                ioTaskExecutor.execute(() -> {
                    updateSendStatusWithRetry(messageId, SeckillMessage.STATUS_SEND_SUCCESS,
                            sendResult.getMsgId(), null);
                });
            }

            @Override
            public void onException(Throwable e) {
                log.error("【可靠消息】MQ 发送失败，消息 ID: {}，补偿任务将自动重发", messageId, e);
                // 异步更新 MySQL 发送状态为失败（补偿任务会扫描并重发）
                ioTaskExecutor.execute(() -> {
                    String failReason = e.getMessage();
                    if (failReason != null && failReason.length() > 200) {
                        failReason = failReason.substring(0, 200);
                    }
                    updateSendStatusWithRetry(messageId, SeckillMessage.STATUS_SEND_FAILED,
                            null, failReason);
                });
            }
        });

        return messageId;
    }

    /**
     * 重发消息（供定时补偿任务调用）
     * <p>
     * 从 MySQL 本地消息表中取出失败/超时的消息，重新发送到 MQ
     * </p>
     *
     * @param message 本地消息表中的消息记录
     * @return 是否发送成功
     */
    public boolean retrySend(SeckillMessage message) {
        if (message == null || message.getMessageId() == null) {
            return false;
        }

        String messageContent = message.getMessageContent();
        if (messageContent == null || messageContent.isEmpty()) {
            // 兼容：如果消息内容丢失，重新拼接
            messageContent = message.getMessageId() + ":" + message.getUserId() + ":" + message.getSeckillProductId();
        }

        try {
            SendResult sendResult = rocketMQTemplate.syncSend(SECKILL_TOPIC, messageContent, 5000);
            // 同步发送成功，更新状态
            seckillMessageMapper.updateSendStatus(message.getId(),
                    SeckillMessage.STATUS_SEND_SUCCESS, sendResult.getMsgId());
            log.info("【消息补偿】重发成功，消息 ID: {}, MQ msgId: {}", message.getMessageId(), sendResult.getMsgId());
            return true;
        } catch (Exception e) {
            // 区分系统异常和业务异常，系统异常需要告警
            String failReason = e.getMessage();
            if (failReason != null && failReason.length() > 200) {
                failReason = failReason.substring(0, 200);
            }
            seckillMessageMapper.incrementRetryCount(message.getId(),
                    SeckillMessage.STATUS_SEND_FAILED, failReason);
            
            // 判断是否为系统级异常，需要立即告警
            boolean isSystemException = isSystemException(e);
            if (isSystemException) {
                log.error("【消息补偿】系统级异常，需要立即关注！消息 ID: {}, 重试次数：{}", 
                        message.getMessageId(), message.getRetryCount() + 1, e);
            } else {
                log.error("【消息补偿】重发失败，消息 ID: {}, 重试次数：{}", 
                        message.getMessageId(), message.getRetryCount() + 1, e);
            }
            return false;
        }
    }

    /**
     * 判断是否为系统级异常（需要立即告警）
     *
     * @param e 异常
     * @return true=系统异常，false=业务异常
     */
    private boolean isSystemException(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // 数据库连接异常
        if (message.contains("Connection") || message.contains("database") || message.contains("MySQL")) {
            return true;
        }
        // Redis 异常
        if (message.contains("Redis") || message.contains("Jedis") || message.contains("redis")) {
            return true;
        }
        // MQ 连接异常
        if (message.contains("RocketMQ") || message.contains("broker") || message.contains("nameserver")) {
            return true;
        }
        // 网络异常
        if (message.contains("timeout") || message.contains("connect") || message.contains("network")) {
            return true;
        }
        return false;
    }

    /**
     * 确认消费成功 —— 更新 MySQL 本地消息表
     *
     * @param messageId 消息唯一 ID
     */
    public void confirmConsumeSuccess(String messageId) {
        log.info("【消费确认】消费成功，消息 ID: {}", messageId);
        try {
            seckillMessageMapper.updateConsumeStatus(messageId, SeckillMessage.CONSUME_STATUS_SUCCESS);
        } catch (Exception e) {
            log.error("【消费确认】更新消费成功状态失败，消息 ID: {}", messageId, e);
            throw new RuntimeException("更新消费成功状态失败", e);
        }
    }

    /**
     * 两阶段确认 - 第一阶段：预确认消费
     * 记录消费开始时间，用于追踪消费耗时和防止重复消费
     *
     * @param messageId 消息唯一 ID
     * @return true=预确认成功，false=消息不存在或状态异常
     */
    public boolean preConfirmConsume(String messageId) {
        log.debug("【两阶段确认】预确认消费，消息 ID: {}", messageId);
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            int affected = seckillMessageMapper.preConfirmConsume(messageId, now);
            if (affected == 0) {
                log.warn("【两阶段确认】预确认失败，消息不存在或状态异常，消息 ID: {}", messageId);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("【两阶段确认】预确认异常，消息 ID: {}", messageId, e);
            return false;
        }
    }

    /**
     * 两阶段确认 - 第二阶段：最终确认消费
     * 使用版本号控制，防止并发覆盖
     *
     * @param messageId 消息唯一 ID
     * @param consumeStatus 消费状态
     * @return true=确认成功，false=版本不匹配或消息不存在
     */
    public boolean finalConfirmConsume(String messageId, int consumeStatus) {
        log.debug("【两阶段确认】最终确认消费，消息 ID: {}, 状态：{}", messageId, consumeStatus);
        try {
            // 获取当前版本号
            Long currentVersion = seckillMessageMapper.getVersionByMessageId(messageId);
            if (currentVersion == null) {
                log.error("【两阶段确认】最终确认失败，消息不存在，消息 ID: {}", messageId);
                return false;
            }

            // 使用版本号更新（乐观锁）
            int affected = seckillMessageMapper.updateConsumeStatusWithVersion(messageId, consumeStatus, currentVersion);
            if (affected == 0) {
                log.warn("【两阶段确认】最终确认失败，版本不匹配，消息 ID: {}, 当前版本：{}", messageId, currentVersion);
                return false;
            }

            log.info("【两阶段确认】最终确认成功，消息 ID: {}, 状态：{}, 新版本：{}", messageId, consumeStatus, currentVersion + 1);
            return true;
        } catch (Exception e) {
            log.error("【两阶段确认】最终确认异常，消息 ID: {}", messageId, e);
            return false;
        }
    }

    /**
     * 确认消费失败 —— 同步更新 MySQL 本地消息表（用于 MQ 重试耗尽后的最终标记）
     * 必须同步执行，确保定时任务能正确读取状态
     *
     * @param messageId  消息唯一 ID
     * @param failReason 失败原因
     */
    public void confirmConsumeFailureSync(String messageId, String failReason) {
        log.warn("【消费确认】消费失败（最终标记），消息 ID: {}, 原因：{}", messageId, failReason);
        try {
            if (failReason != null && failReason.length() > 500) {
                seckillMessageMapper.updateConsumeFailure(messageId,
                        SeckillMessage.CONSUME_STATUS_FAILED, failReason.substring(0, 500));
            } else {
                seckillMessageMapper.updateConsumeFailure(messageId,
                        SeckillMessage.CONSUME_STATUS_FAILED, failReason);
            }
        } catch (Exception e) {
            log.error("【消费确认】更新消费失败状态失败，消息 ID: {}", messageId, e);
        }
    }

    /**
     * 确认消费失败 —— 异步更新（已废弃，避免竞态条件）
     * 保留用于兼容旧代码，但推荐使用 confirmConsumeFailureSync
     *
     * @param messageId  消息唯一 ID
     * @param failReason 失败原因
     */
    @Deprecated
    public void confirmConsumeFailure(String messageId, String failReason) {
        log.warn("【消费确认】消费失败（异步，不推荐），消息 ID: {}, 原因：{}", messageId, failReason);
    }

    /**
     * 发送状态更新重试，避免回调线程偶发异常导致状态长期不准确
     */
    private void updateSendStatusWithRetry(String messageId, int status, String mqMessageId, String failReason) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                SeckillMessage dbMsg = seckillMessageMapper.findByMessageId(messageId);
                if (dbMsg == null) {
                    log.error("【可靠消息】更新发送状态失败，消息不存在，消息 ID: {}", messageId);
                    return;
                }
                if (status == SeckillMessage.STATUS_SEND_SUCCESS) {
                    seckillMessageMapper.updateSendStatus(dbMsg.getId(), status, mqMessageId);
                } else {
                    seckillMessageMapper.incrementRetryCount(dbMsg.getId(), status, failReason);
                }
                return;
            } catch (Exception ex) {
                if (retry == 2) {
                    log.error("【可靠消息】更新发送状态失败（已达最大重试次数），消息 ID: {}, 状态：{}", messageId, status, ex);
                    return;
                }
                try {
                    Thread.sleep(50L * (retry + 1));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    log.error("【可靠消息】更新发送状态重试被中断，消息 ID: {}", messageId, interruptedException);
                    return;
                }
            }
        }
    }
}
