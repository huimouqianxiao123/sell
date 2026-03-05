package com.example.sell.scheduler;

import com.example.sell.dao.OrderMapper;
import com.example.sell.dao.SeckillMessageMapper;
import com.example.sell.dao.SeckillProductMapper;
import com.example.sell.domain.enums.DeadLetterReason;
import com.example.sell.domain.pojo.SeckillMessage;
import com.example.sell.service.Imp.AlertService;
import com.example.sell.service.Imp.RocketMQMessageService;
import com.example.sell.utils.DistributedLockUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀消息补偿定时任务
 * 
 * 负责处理以下场景：
 * 1. 发送失败的消息：定时重发
 * 2. 消费失败的消息：重新发送到MQ
 * 3. 超时未消费的消息：告警并重发
 * 4. 死信消息：告警并记录，等待人工处理
 * 
 * @author 屈轩
 */
@Slf4j
@Component
public class SeckillMessageCompensationTask {

    @Resource
    private SeckillMessageMapper seckillMessageMapper;

    @Resource
    private RocketMQMessageService rocketMQMessageService;


    @Resource
    private OrderMapper orderMapper;

    @Resource
    private SeckillProductMapper seckillProductMapper;

    @Resource
    private DistributedLockUtil distributedLockUtil;

    @Resource
    private AlertService alertService;

    @Resource(name = "ioTaskExecutor")
    private ThreadPoolTaskExecutor ioTaskExecutor;

    /**
     * 补偿发送失败的消息
     * 每30秒执行一次，扫描发送失败的消息进行重发
     */
    @Scheduled(cron = "0/30 * * * * ?")
    public void compensateSendFailedMessages() {
        String lockKey = "seckill:message:compensation:send:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 25, TimeUnit.SECONDS);
        if (lockValue == null) {
            return; // 其他实例正在处理
        }

        try {
            log.debug("【消息补偿】开始扫描发送失败的消息...");

            // 查询待发送或发送失败的消息
            List<SeckillMessage> pendingMessages = seckillMessageMapper.findPendingMessages(
                    SeckillMessage.MAX_SEND_RETRY, 100);

            if (pendingMessages.isEmpty()) {
                log.debug("【消息补偿】暂无需要重发的消息");
                return;
            }

            log.info("【消息补偿】发现 {} 条需要重发的消息", pendingMessages.size());

            int successCount = 0;
            int failCount = 0;
            int needAlertCount = 0;

            for (SeckillMessage message : pendingMessages) {
                try {
                    boolean success = rocketMQMessageService.retrySend(message);
                    if (success) {
                        successCount++;
                    } else {
                        failCount++;
                        // 检查是否需要告警（重试次数接近上限）
                        if (message.getRetryCount() >= SeckillMessage.MAX_SEND_RETRY - 1) {
                            needAlertCount++;
                            alertService.alertStockAnomaly(
                                    message.getSeckillProductId(),
                                    message.getUserId(),
                                    String.format("消息发送重试即将耗尽 (重试次数：%d/%d, 消息 ID: %s)", 
                                            message.getRetryCount(), 
                                            SeckillMessage.MAX_SEND_RETRY,
                                            message.getMessageId()));
                        }
                    }
                    // 避免发送过快
                    Thread.sleep(50);
                } catch (Exception e) {
                    failCount++;
                    log.error("【消息补偿】消息重发异常，消息 ID: {}", message.getMessageId(), e);
                    // 检查是否需要告警
                    if (message.getRetryCount() >= SeckillMessage.MAX_SEND_RETRY - 1) {
                        needAlertCount++;
                        alertService.alertStockAnomaly(
                                message.getSeckillProductId(),
                                message.getUserId(),
                                String.format("消息重发异常且重试即将耗尽 (重试次数：%d/%d, 消息 ID: %s)", 
                                        message.getRetryCount(), 
                                        SeckillMessage.MAX_SEND_RETRY,
                                        message.getMessageId()));
                    }
                }
            }

            log.info("【消息补偿】消息重发完成，成功：{}, 失败：{}, 告警：{}", successCount, failCount, needAlertCount);

        } catch (Exception e) {
            log.error("【消息补偿】发送补偿任务异常", e);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 补偿消费失败的消息
     * 每分钟执行一次，扫描消费失败的消息，重新发送到MQ
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void compensateConsumeFailedMessages() {
        String lockKey = "seckill:message:compensation:consume:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 55, TimeUnit.SECONDS);
        if (lockValue == null) {
            return;
        }

        try {
            log.debug("【消费补偿】开始扫描消费失败的消息...");

            List<SeckillMessage> failedMessages = seckillMessageMapper.findConsumeFailedMessages(
                    SeckillMessage.MAX_CONSUME_RETRY, 50);

            if (failedMessages.isEmpty()) {
                log.debug("【消费补偿】暂无需要重新消费的消息");
                return;
            }

            log.info("【消费补偿】发现 {} 条消费失败的消息，准备重发", failedMessages.size());

            int successCount = 0;
            int skippedCount = 0;
            for (SeckillMessage message : failedMessages) {
                try {
                    // 检查订单是否已存在（防止重复创建订单）
                    // 通过查询seckill_product表获取productId，再检查订单
                    boolean orderExists = checkOrderExists(message.getUserId(), message.getSeckillProductId());
                    if (orderExists) {
                        log.info("【消费补偿】订单已存在，直接标记为消费成功，消息ID: {}, 用户ID: {}, 商品ID: {}",
                                message.getMessageId(), message.getUserId(), message.getSeckillProductId());
                        seckillMessageMapper.updateConsumeStatus(
                                message.getMessageId(),
                                SeckillMessage.CONSUME_STATUS_SUCCESS);
                        skippedCount++;
                        continue;
                    }

                    // 重置消费状态为待消费
                    seckillMessageMapper.updateConsumeStatus(
                            message.getMessageId(),
                            SeckillMessage.CONSUME_STATUS_PENDING);

                    // 重新发送到MQ
                    boolean success = rocketMQMessageService.retrySend(message);
                    if (success) {
                        successCount++;
                    }
                    Thread.sleep(100);
                } catch (Exception e) {
                    log.error("【消费补偿】消息重发异常，消息ID: {}", message.getMessageId(), e);
                }
            }

            log.info("【消费补偿】消费失败消息重发完成，成功: {}, 跳过(订单已存在): {}", successCount, skippedCount);

        } catch (Exception e) {
            log.error("【消费补偿】消费补偿任务异常", e);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 检测超时未消费的消息
     * 每5分钟执行一次，检测发送成功但长时间未消费的消息
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void checkTimeoutMessages() {
        String lockKey = "seckill:message:compensation:timeout:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 240, TimeUnit.SECONDS);
        if (lockValue == null) {
            return;
        }

        try {
            log.debug("【超时检测】开始扫描超时未消费的消息...");

            // 查询发送成功但超过10分钟未消费的消息
            List<SeckillMessage> timeoutMessages = seckillMessageMapper.findTimeoutMessages(10, 50);

            if (timeoutMessages.isEmpty()) {
                log.debug("【超时检测】暂无超时未消费的消息");
                return;
            }

            log.warn("【超时检测】发现 {} 条超时未消费的消息，准备重发", timeoutMessages.size());

            for (SeckillMessage message : timeoutMessages) {
                try {
                    // 重新发送到MQ
                    boolean success = rocketMQMessageService.retrySend(message);
                    if (success) {
                        log.info("【超时检测】超时消息重发成功，消息ID: {}, 用户ID: {}, 商品ID: {}",
                                message.getMessageId(), message.getUserId(), message.getSeckillProductId());
                    }
                    Thread.sleep(100);
                } catch (Exception e) {
                    log.error("【超时检测】超时消息重发异常，消息ID: {}", message.getMessageId(), e);
                }
            }

        } catch (Exception e) {
            log.error("【超时检测】超时检测任务异常", e);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 处理死信消息
        * 每小时执行一次，检测超过普通重试上限的消息，进入死信补偿重试
     */
    @Scheduled(cron = "0 0 */1 * * ?")
    public void handleDeadLetterMessages() {
        String lockKey = "seckill:message:compensation:dead:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 3500, TimeUnit.SECONDS);
        if (lockValue == null) {
            return;
        }

        try {
            log.info("【死信处理】开始扫描死信消息...");

            List<SeckillMessage> deadLetterMessages = seckillMessageMapper.findDeadLetterMessages(
                    SeckillMessage.MAX_SEND_RETRY,
                    SeckillMessage.MAX_CONSUME_RETRY,
                    100);

            if (deadLetterMessages.isEmpty()) {
                log.info("【死信处理】暂无死信消息");
                return;
            }

            int retriedCount = 0;
            int exceededCount = 0;

            for (SeckillMessage message : deadLetterMessages) {
                boolean consumeDeadLetter = message.getStatus() == SeckillMessage.STATUS_SEND_SUCCESS
                        && message.getConsumeStatus() != null
                        && message.getConsumeStatus() == SeckillMessage.CONSUME_STATUS_FAILED;

                if (consumeDeadLetter) {
                    int consumeRetryCount = message.getConsumeRetryCount() == null ? 0 : message.getConsumeRetryCount();
                    if (consumeRetryCount < SeckillMessage.MAX_DEAD_LETTER_RETRY) {
                        try {
                            seckillMessageMapper.updateConsumeStatus(
                                    message.getMessageId(),
                                    SeckillMessage.CONSUME_STATUS_PENDING);
                            boolean success = rocketMQMessageService.retrySend(message);
                            if (success) {
                                retriedCount++;
                                log.warn("【死信处理】死信消息已重投，消息ID: {}, 当前消费重试次数: {}",
                                        message.getMessageId(), consumeRetryCount);
                            }
                        } catch (Exception e) {
                            log.error("【死信处理】死信消息重投异常，消息ID: {}", message.getMessageId(), e);
                        }
                        continue;
                    }
                }

                exceededCount++;
                log.error("【死信处理】死信消息超过重试上限 - 消息ID: {}, 用户ID: {}, 商品ID: {}, " +
                                "发送状态: {}, 消费状态: {}, 发送重试次数: {}, 消费重试次数: {}, 失败原因: {}",
                        message.getMessageId(),
                        message.getUserId(),
                        message.getSeckillProductId(),
                        message.getStatus(),
                        message.getConsumeStatus(),
                        message.getRetryCount(),
                        message.getConsumeRetryCount(),
                        message.getFailReason());
            }

            if (retriedCount > 0) {
                log.warn("【死信处理】本轮已重投死信消息: {} 条", retriedCount);
            }
            if (exceededCount > 0) {
                log.error("【死信处理】仍有 {} 条死信消息超过上限，需要人工处理", exceededCount);
                alertService.alertDeadLetterMessages(deadLetterMessages);
            }

        } catch (Exception e) {
            log.error("【死信处理】死信处理任务异常", e);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 消息统计（每小时统计一次消息处理情况）
     */
    @Scheduled(cron = "0 30 */1 * * ?")
    public void statisticsMessages() {
        try {
            List<SeckillMessage> pendingMessages = seckillMessageMapper.findPendingMessages(
                    SeckillMessage.MAX_SEND_RETRY, 1000);
            List<SeckillMessage> consumeFailedMessages = seckillMessageMapper.findConsumeFailedMessages(
                    SeckillMessage.MAX_CONSUME_RETRY, 1000);
            List<SeckillMessage> deadLetterMessages = seckillMessageMapper.findDeadLetterMessages(
                    SeckillMessage.MAX_SEND_RETRY,
                    SeckillMessage.MAX_CONSUME_RETRY,
                    1000);

            log.info("【消息统计】待发送/发送失败: {}, 消费失败: {}, 死信: {}",
                    pendingMessages.size(),
                    consumeFailedMessages.size(),
                    deadLetterMessages.size());

        } catch (Exception e) {
            log.error("【消息统计】统计任务异常", e);
        }
    }

    /**
     * 死信消息自动分类处理
     * 每 10 分钟执行一次，根据死信原因自动处理
     * - 临时故障：立即重试
     * - 系统故障：延迟重试
     * - 业务异常：记录日志，发送告警，不重试
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void autoRecoverDeadLetterMessages() {
        String lockKey = "seckill:message:compensation:auto-recover:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 540, TimeUnit.SECONDS);
        if (lockValue == null) {
            return;
        }

        try {
            log.info("【死信自动恢复】开始扫描死信消息...");

            List<SeckillMessage> deadLetterMessages = seckillMessageMapper.findDeadLetterMessages(
                    SeckillMessage.MAX_SEND_RETRY,
                    SeckillMessage.MAX_CONSUME_RETRY,
                    50);

            if (deadLetterMessages.isEmpty()) {
                log.debug("【死信自动恢复】暂无死信消息");
                return;
            }

            int temporaryCount = 0;
            int systemCount = 0;
            int businessCount = 0;
            int unknownCount = 0;

            for (SeckillMessage message : deadLetterMessages) {
                try {
                    // 分析死信原因
                    DeadLetterReason reason = analyzeDeadLetterReason(message);

                    switch (reason) {
                        case TEMPORARY:
                            // 临时故障：立即重试
                            log.info("【死信自动恢复】临时故障，立即重试，消息 ID: {}, 原因：{}", 
                                    message.getMessageId(), message.getFailReason());
                            retryDeadLetterMessage(message);
                            temporaryCount++;
                            break;

                        case SYSTEM:
                            // 系统故障：延迟 5 分钟后重试
                            log.info("【死信自动恢复】系统故障，延迟重试，消息 ID: {}, 原因：{}", 
                                    message.getMessageId(), message.getFailReason());
                            scheduleDelayedRetry(message, 5, TimeUnit.MINUTES);
                            systemCount++;
                            break;

                        case BUSINESS:
                            // 业务异常：记录日志，发送告警，不重试
                            log.warn("【死信自动恢复】业务异常，不重试，消息 ID: {}, 原因：{}", 
                                    message.getMessageId(), message.getFailReason());
                            alertService.alertStockAnomaly(
                                    message.getSeckillProductId(),
                                    message.getUserId(),
                                    String.format("死信消息 - 业务异常 (消息 ID: %s, 原因：%s)", 
                                            message.getMessageId(), 
                                            message.getFailReason()));
                            businessCount++;
                            break;

                        default:
                            // 未知原因：记录日志
                            log.error("【死信自动恢复】未知原因，需要人工介入，消息 ID: {}, 失败原因：{}", 
                                    message.getMessageId(), message.getFailReason());
                            unknownCount++;
                            break;
                    }
                } catch (Exception e) {
                    log.error("【死信自动恢复】处理死信消息异常，消息 ID: {}", message.getMessageId(), e);
                }
            }

            log.info("【死信自动恢复】处理完成，临时故障：{}, 系统故障：{}, 业务异常：{}, 未知原因：{}", 
                    temporaryCount, systemCount, businessCount, unknownCount);

        } catch (Exception e) {
            log.error("【死信自动恢复】死信自动恢复任务异常", e);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 分析死信原因
     *
     * @param message 死信消息
     * @return 死信原因
     */
    private DeadLetterReason analyzeDeadLetterReason(SeckillMessage message) {
        String failReason = message.getFailReason();
        if (failReason == null || failReason.isEmpty()) {
            return DeadLetterReason.UNKNOWN;
        }

        String lowerFailReason = failReason.toLowerCase();

        // 业务异常特征
        if (failReason.contains("重复购买") ||
            failReason.contains("重复消费") ||
            failReason.contains("库存不足") ||
            failReason.contains("商品不存在") ||
            failReason.contains("秒杀活动未开始") ||
            failReason.contains("秒杀活动已结束") ||
            lowerFailReason.contains("duplicate") ||
            lowerFailReason.contains("not found") ||
            lowerFailReason.contains("not exist")) {
            return DeadLetterReason.BUSINESS;
        }

        // 临时故障特征
        if (failReason.contains("timeout") ||
            failReason.contains("Timeout") ||
            failReason.contains("Connection") ||
            failReason.contains("connection") ||
            failReason.contains("Lock") ||
            failReason.contains("lock") ||
            failReason.contains("Deadlock") ||
            lowerFailReason.contains("network") ||
            lowerFailReason.contains("socket")) {
            return DeadLetterReason.TEMPORARY;
        }

        // 系统故障特征
        if (failReason.contains("Redis") ||
            failReason.contains("redis") ||
            failReason.contains("Jedis") ||
            failReason.contains("RocketMQ") ||
            failReason.contains("rocketmq") ||
            failReason.contains("broker") ||
            failReason.contains("NameServer") ||
            failReason.contains("MySQL") ||
            failReason.contains("database") ||
            failReason.contains("DataSource") ||
            lowerFailReason.contains("pool") ||
            lowerFailReason.contains("exhausted")) {
            return DeadLetterReason.SYSTEM;
        }

        // 默认为未知原因
        return DeadLetterReason.UNKNOWN;
    }

    /**
     * 重试死信消息
     *
     * @param message 死信消息
     */
    private void retryDeadLetterMessage(SeckillMessage message) {
        try {
            // 重置消费状态为待消费
            seckillMessageMapper.updateConsumeStatus(
                    message.getMessageId(),
                    SeckillMessage.CONSUME_STATUS_PENDING);

            // 重新发送到 MQ
            boolean success = rocketMQMessageService.retrySend(message);
            if (success) {
                log.info("【死信自动恢复】死信消息重投成功，消息 ID: {}", message.getMessageId());
            } else {
                log.error("【死信自动恢复】死信消息重投失败，消息 ID: {}", message.getMessageId());
            }
        } catch (Exception e) {
            log.error("【死信自动恢复】死信消息重投异常，消息 ID: {}", message.getMessageId(), e);
        }
    }

    /**
     * 调度延迟重试
     *
     * @param message 死信消息
     * @param delay 延迟时间
     * @param unit 时间单位
     */
    private void scheduleDelayedRetry(SeckillMessage message, long delay, TimeUnit unit) {
        try {
            // 使用异步任务实现延迟重试
            ioTaskExecutor.execute(() -> {
                try {
                    log.info("【死信自动恢复】延迟重试开始，消息 ID: {}, 延迟：{} {}", 
                            message.getMessageId(), delay, unit);
                    
                    // 延迟执行
                    unit.sleep(delay);

                    // 重置消费状态为待消费
                    seckillMessageMapper.updateConsumeStatus(
                            message.getMessageId(),
                            SeckillMessage.CONSUME_STATUS_PENDING);

                    // 重新发送到 MQ
                    boolean success = rocketMQMessageService.retrySend(message);
                    if (success) {
                        log.info("【死信自动恢复】延迟重试成功，消息 ID: {}", message.getMessageId());
                    } else {
                        log.error("【死信自动恢复】延迟重试失败，消息 ID: {}", message.getMessageId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("【死信自动恢复】延迟重试被中断，消息 ID: {}", message.getMessageId(), e);
                } catch (Exception e) {
                    log.error("【死信自动恢复】延迟重试异常，消息 ID: {}", message.getMessageId(), e);
                }
            });
        } catch (Exception e) {
            log.error("【死信自动恢复】调度延迟重试失败，消息 ID: {}", message.getMessageId(), e);
        }
    }

    /**
     * 检查订单是否已存在
     * 用于消费失败重试前判断是否需要重试
     * 只检查秒杀订单（order_type = 2），避免误判普通订单
     *
     * @param userId 用户ID
     * @param seckillProductId 秒杀商品ID
     * @return true表示秒杀订单已存在，false表示秒杀订单不存在
     */
    private boolean checkOrderExists(Long userId, Long seckillProductId) {
        try {
            com.example.sell.domain.pojo.SeckillProduct seckillProduct = seckillProductMapper.selectById(seckillProductId);
            if (seckillProduct == null) {
                log.warn("【消息补偿】秒杀商品不存在，ID: {}", seckillProductId);
                return false;
            }
            int count = orderMapper.countSeckillOrderByUserAndProduct(userId, seckillProduct.getProductId());
            return count > 0;
        } catch (Exception e) {
            log.error("【消息补偿】检查订单是否存在失败，用户ID: {}, 商品ID: {}", userId, seckillProductId, e);
            return false;
        }
    }
}
