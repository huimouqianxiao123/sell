package com.example.sell.consumer;

import com.example.sell.service.impl.RocketMQMessageService;
import com.example.sell.service.impl.SeckillIdempotentService;
import com.example.sell.service.impl.SeckillOrderService;
import com.example.sell.service.impl.SeckillRollbackService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * @author 屈轩
 * 秒杀订单消费者
 * <p>
 * 幂等性保证策略：
 * 1. Redis SETNX 实现分布式幂等锁（前置检查）
 * 2. MySQL库存扣减使用 stock > 0 条件（防止超卖）
 * 3. 事务回滚时清理幂等标记（保证重试可用）
 * <p>
 * 消费确认机制：
 * 1. 消费成功后更新本地消息表状态为已消费
 * 2. 消费失败后：
 *    - 若未达最大重试次数（3次）：仅回滚Redis，清理幂等，抛出异常触发MQ重试，不更新MySQL状态
 *    - 若已达最大重试次数：同步更新MySQL为消费失败，由定时任务接管
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq.listener", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(topic = "seckill-topic", consumerGroup = "seckill-order-group", consumeMode = org.apache.rocketmq.spring.annotation.ConsumeMode.CONCURRENTLY, messageModel = org.apache.rocketmq.spring.annotation.MessageModel.CLUSTERING, maxReconsumeTimes = 3)
public class MiaoShaOrderConsumer implements RocketMQListener<MessageExt> {


    @Resource
    private SeckillOrderService seckillOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RocketMQMessageService rocketMQMessageService;
    @Resource
    private SeckillIdempotentService seckillIdempotentService;
    @Resource
    private SeckillRollbackService seckillRollbackService;

    private static final String ORDER_KEY_PREFIX = "seckill:order:";
    private static final String SECKILL_PENDING_KEY_PREFIX = "seckill:pending:";
    private static final long IDEMPOTENT_EXPIRE_HOURS = 24;
    private static final int MAX_RECONSUME_TIMES = 3;

    /**
     * 处理秒杀订单消息
     * 支持两种消息格式：
     * 1. 新格式：messageId:userId:seckillProductId（可靠消息）
     * 2. 旧格式：userId:seckillProductId（兼容旧版）
     *
     * @param messageExt 消息对象（包含重试次数等元数据）
     */
    @Override
    public void onMessage(MessageExt messageExt) {
        String msg = new String(messageExt.getBody());
        int reconsumeTimes = messageExt.getReconsumeTimes();
        log.info("【秒杀订单】收到秒杀消息: {}, 重试次数: {}", msg, reconsumeTimes);

        String[] parts = msg.split(":");
        String messageId = null;
        Long userId;
        Long seckillProductId;
        Long productId = null;
        if (parts.length >= 3) {
            messageId = parts[0];
            userId = Long.valueOf(parts[1]);
            seckillProductId = Long.valueOf(parts[2]);
            if (parts.length > 3) {
                productId = Long.valueOf(parts[3]);
            }
        } else if (parts.length == 2) {
            userId = Long.valueOf(parts[0]);
            seckillProductId = Long.valueOf(parts[1]);
        } else {
            log.error("【秒杀订单】消息格式错误: {}", msg);
            throw new RuntimeException("消息格式错误: " + msg);
        }

        String consumeIdempotentKey = messageId != null ? messageId : "legacy:" + userId + ":" + seckillProductId;
        if (!seckillIdempotentService.checkAndSetConsume(consumeIdempotentKey)) {
            log.info("【秒杀订单】消息已消费，跳过重复消费，用户ID: {}, 商品ID: {}, 幂等Key: {}", userId, seckillProductId, consumeIdempotentKey);
            return;
        }

        try {
            String orderNo = seckillOrderService.processOrder(userId, seckillProductId, productId);
            String orderKey = ORDER_KEY_PREFIX + userId + ":" + seckillProductId;
            stringRedisTemplate.opsForValue().set(orderKey, orderNo, IDEMPOTENT_EXPIRE_HOURS, TimeUnit.HOURS);
            String seckillProductOrderNoKey = "seckill:product:order:no:" + userId;
            stringRedisTemplate.opsForValue().set(seckillProductOrderNoKey, orderNo, 50, TimeUnit.SECONDS);
            if (messageId != null) {
                rocketMQMessageService.confirmConsumeSuccess(messageId);
            }
            removePendingEntry(userId, seckillProductId);
            log.info("【秒杀订单】订单创建成功，订单号: {}, 用户ID: {}, 消息ID: {}", orderNo, userId, messageId);
        } catch (org.springframework.data.redis.RedisSystemException e) {
            if (e.getCause() instanceof io.lettuce.core.RedisCommandInterruptedException) {
                log.warn("【秒杀订单】Redis命令被中断（应用可能正在关闭），触发MQ重试，用户ID: {}, 商品ID: {}", userId, seckillProductId);
                handleConsumeFailure(userId, seckillProductId, consumeIdempotentKey, messageId, reconsumeTimes, "Redis命令被中断", e);
                return;
            }
            handleConsumeFailure(userId, seckillProductId, consumeIdempotentKey, messageId, reconsumeTimes, "Redis操作失败: " + e.getMessage(), e);
        } catch (Exception e) {
            handleConsumeFailure(userId, seckillProductId, consumeIdempotentKey, messageId, reconsumeTimes, e.getMessage(), e);
        }
    }

    /**
     * 统一处理消费失败逻辑
     * 
     * 核心设计原则：
     * - MQ重试期间：保持Redis库存占用状态，只清除消费幂等标记，让重试有机会成功
     * - MQ重试耗尽后：不回滚Redis库存，更新MySQL为消费失败，由定时任务统一对账处理
     * 
     * 这样设计的原因：
     * 1. 用户抢到了库存（Redis扣减成功），应该给用户多次重试机会
     * 2. 如果每次失败都回滚库存，MQ重试时库存可能已被其他用户抢走
     * 3. MQ重试耗尽后不立即回滚，让定时任务有机会补偿成功
     * 4. 定时任务会检查订单是否已存在、数据库是否恢复等条件后再决定重试或回滚
     * 
     * RocketMQ reconsumeTimes 说明：
     * - reconsumeTimes = 0：第一次消费（原始消息）
     * - reconsumeTimes = 1：第一次重试
     * - reconsumeTimes = 2：第二次重试
     * - reconsumeTimes = 3：第三次重试（最后一次，由 maxReconsumeTimes=3 决定）
     *
     * @param userId           用户ID
     * @param seckillProductId 秒杀商品ID
     * @param consumeIdempotentKey 幂等Key
     * @param messageId        消息ID
     * @param reconsumeTimes   当前重试次数
     * @param failReason       失败原因
     * @param e                异常对象
     */
    private void handleConsumeFailure(Long userId, Long seckillProductId, String consumeIdempotentKey,
                                       String messageId, int reconsumeTimes, String failReason, Exception e) {
        log.error("【秒杀订单】处理失败，用户ID: {}, 商品ID: {}, 重试次数: {}", userId, seckillProductId, reconsumeTimes, e);

        boolean isLastRetry = (reconsumeTimes >= MAX_RECONSUME_TIMES);
        
        if (isLastRetry) {
            log.warn("【秒杀订单】已达最大重试次数({}), 不回滚Redis库存，标记进入死信队列并由死信任务继续重试，消息ID: {}", 
                    reconsumeTimes, messageId);
            seckillIdempotentService.removeConsumeKey(consumeIdempotentKey);
            if (messageId != null) {
                rocketMQMessageService.confirmConsumeFailureSync(messageId, failReason);
            }
        } else {
            log.info("【秒杀订单】未达最大重试次数({}/{}), 保持Redis库存占用，清除幂等标记后继续MQ重试，消息ID: {}", 
                    reconsumeTimes, MAX_RECONSUME_TIMES, messageId);
            seckillIdempotentService.removeConsumeKey(consumeIdempotentKey);
            throw new RuntimeException("订单处理失败，触发重试", e);
        }
    }

    /**
     * 清理Redis pending队列中已处理的条目
     * 消费成功表示订单已生成，对账任务无需再处理该条目
     */
    private void removePendingEntry(Long userId, Long seckillProductId) {
        try {
            String pendingKey = SECKILL_PENDING_KEY_PREFIX + seckillProductId;
            stringRedisTemplate.opsForList().remove(pendingKey, 1, String.valueOf(userId));
            log.debug("【秒杀订单】已清理pending队列，用户ID: {}, 商品ID: {}", userId, seckillProductId);
        } catch (Exception e) {
            log.warn("【秒杀订单】清理pending队列失败（不影响业务），用户ID: {}, 商品ID: {}", userId, seckillProductId, e);
        }
    }

}
