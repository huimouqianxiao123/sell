package com.example.sell.consumer;

import com.example.sell.service.Imp.RocketMQMessageService;
import com.example.sell.service.Imp.SeckillOrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀死信消费者
 * <p>
 * 订阅 seckill-order-group 对应的 RocketMQ 原生 DLQ，
 * 对已进入物理死信的消息进行再次处理，提升最终成功率。
 * </p>
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "%DLQ%seckill-order-group",
        consumerGroup = "seckill-order-dlq-group",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING,
        maxReconsumeTimes = 5
)
public class SeckillDeadLetterConsumer implements RocketMQListener<MessageExt> {

    private static final String ORDER_KEY_PREFIX = "seckill:order:";
    private static final String DLQ_IDEMPOTENT_KEY_PREFIX = "seckill:dlq:idempotent:";
    private static final long IDEMPOTENT_EXPIRE_HOURS = 24;

    @Resource
    private SeckillOrderService seckillOrderService;
    @Resource
    private RocketMQMessageService rocketMQMessageService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(MessageExt messageExt) {
        String msg = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        log.warn("【死信消费】收到死信消息：{}, 原始 Topic: {}, 重试次数：{}",
                msg, messageExt.getTopic(), messageExt.getReconsumeTimes());

        String[] parts = msg.split(":");
        if (parts.length < 3) {
            log.error("【死信消费】消息格式错误，无法处理：{}", msg);
            return;
        }

        String messageId = parts[0];
        Long userId = Long.valueOf(parts[1]);
        Long seckillProductId = Long.valueOf(parts[2]);
        Long productId = parts.length > 3 ? Long.valueOf(parts[3]) : null;

        // 增加 Redis 幂等检查，防止死信消息重复消费
        String dlqIdempotentKey = DLQ_IDEMPOTENT_KEY_PREFIX + messageId;
        Boolean isFirstVisit = stringRedisTemplate.opsForValue().setIfAbsent(
                dlqIdempotentKey, "1", IDEMPOTENT_EXPIRE_HOURS, TimeUnit.HOURS);
        
        if (!Boolean.TRUE.equals(isFirstVisit)) {
            log.warn("【死信消费】消息已处理过，跳过重复消费，消息 ID: {}", messageId);
            return;
        }

        try {
            String orderNo = seckillOrderService.processOrder(userId, seckillProductId, productId);
            String orderKey = ORDER_KEY_PREFIX + userId + ":" + seckillProductId;
            stringRedisTemplate.opsForValue().set(orderKey, orderNo, IDEMPOTENT_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.opsForValue().set("seckill:product:order:no:" + userId, orderNo, 50, TimeUnit.SECONDS);
            rocketMQMessageService.confirmConsumeSuccess(messageId);
            log.warn("【死信消费】死信消息处理成功，消息 ID: {}, 订单号：{}", messageId, orderNo);
        } catch (Exception e) {
            log.error("【死信消费】死信消息处理失败，消息 ID: {}", messageId, e);
            rocketMQMessageService.confirmConsumeFailureSync(messageId, "DLQ 消费失败：" + e.getMessage());
            throw new RuntimeException("死信消息处理失败", e);
        }
    }
}
