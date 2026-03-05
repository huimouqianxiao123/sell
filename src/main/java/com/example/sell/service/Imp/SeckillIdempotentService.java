package com.example.sell.service.Imp;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * @author 屈轩
 */
@Slf4j
@Component
public class SeckillIdempotentService {

    private static final String SECKILL_IDEMPOTENT_KEY_PREFIX = "seckill:idempotent:";
    private static final String SECKILL_MESSAGE_KEY_PREFIX = "seckill:message:";
    private static final String SECKILL_CONSUME_KEY_PREFIX = "seckill:consume:";

    private static final long IDEMPOTENT_EXPIRE_SECONDS = 60;
    private static final long MESSAGE_EXPIRE_SECONDS = 600;
    private static final long CONSUME_EXPIRE_SECONDS = 7200;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public boolean checkAndSetIdempotent(Long userId, Long seckillProductId) {
        String key = buildIdempotentKey(userId, seckillProductId);
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", IDEMPOTENT_EXPIRE_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(result)) {
            log.debug("【幂等检查】重复请求，用户ID: {}, 商品ID: {}", userId, seckillProductId);
            return false;
        }
        log.debug("【幂等检查】首次请求，用户ID: {}, 商品ID: {}", userId, seckillProductId);
        return true;
    }

    public boolean checkAndSetMessageSent(String messageId) {
        String key = buildMessageKey(messageId);
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", MESSAGE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(result)) {
            log.debug("【消息幂等】消息已发送，消息ID: {}", messageId);
            return false;
        }
        log.debug("【消息幂等】消息首次发送，消息ID: {}", messageId);
        return true;
    }

    public void saveMessage(String messageId, Long userId, Long seckillProductId, String messageContent) {
        String key = buildMessageContentKey(messageId);
        String value = userId + ":" + seckillProductId + ":" + messageContent;
        stringRedisTemplate.opsForValue().set(key, value, MESSAGE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        log.debug("【消息存储】消息已缓存，消息ID: {}, 用户ID: {}, 商品ID: {}", messageId, userId, seckillProductId);
    }

    public String getMessage(String messageId) {
        String key = buildMessageContentKey(messageId);
        return stringRedisTemplate.opsForValue().get(key);
    }

    public boolean checkAndSetConsume(String messageId) {
        String key = buildConsumeKey(messageId);
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", CONSUME_EXPIRE_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(result)) {
            log.debug("【消费幂等】消息已消费，消息ID: {}", messageId);
            return false;
        }
        log.debug("【消费幂等】消息首次消费，消息ID: {}", messageId);
        return true;
    }

    public void removeIdempotent(Long userId, Long seckillProductId) {
        String key = buildIdempotentKey(userId, seckillProductId);
        stringRedisTemplate.delete(key);
        log.debug("【幂等清理】清理幂等标记，用户ID: {}, 商品ID: {}", userId, seckillProductId);
    }

    public void removeMessageSent(String messageId) {
        String key = buildMessageKey(messageId);
        stringRedisTemplate.delete(key);
        log.debug("【消息清理】清理消息标记，消息ID: {}", messageId);
    }

    public void removeConsumeKey(String messageId) {
        String key = buildConsumeKey(messageId);
        stringRedisTemplate.delete(key);
        log.debug("【消费幂等清理】清理消费幂等标记，消息ID: {}", messageId);
    }

    private String buildIdempotentKey(Long userId, Long seckillProductId) {
        return SECKILL_IDEMPOTENT_KEY_PREFIX + userId + ":" + seckillProductId;
    }

    private String buildMessageKey(String messageId) {
        return SECKILL_MESSAGE_KEY_PREFIX + messageId;
    }

    private String buildMessageContentKey(String messageId) {
        return SECKILL_MESSAGE_KEY_PREFIX + "content:" + messageId;
    }

    private String buildConsumeKey(String messageId) {
        return SECKILL_CONSUME_KEY_PREFIX + messageId;
    }
}
