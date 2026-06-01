package com.example.sell.controller;

import com.example.sell.common.R;
import com.example.sell.entity.SeckillProduct;
import com.example.sell.service.SeckillProductService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 测试控制器 - 用于排查问题
 */
@RestController
@RequestMapping("/test")
public class TestController {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillProductService seckillProductService;

    private static final String SECKILL_PRODUCT_KEY = "seckillProduct:";
    private static final String SECKILL_STOCK_KEY = "seckill:stock:";

    /**
     * 手动预热指定商品
     */
    @PostMapping("/warmup/{id}")
    public R<String> manualWarmup(@PathVariable Long id) {
        SeckillProduct seckillProduct = seckillProductService.getById(id);
        if (seckillProduct == null) {
            return R.error("商品不存在");
        }

        String productKey = SECKILL_PRODUCT_KEY + id;
        String stockKey = SECKILL_STOCK_KEY + id;

        LocalDateTime now = LocalDateTime.now();
        long expireSeconds = seckillProduct.getEndTime() == null
                ? TimeUnit.HOURS.toSeconds(24)
                : ChronoUnit.SECONDS.between(now, seckillProduct.getEndTime().plusHours(24));
        if (expireSeconds <= 0) {
            expireSeconds = TimeUnit.HOURS.toSeconds(24);
        }

        redisTemplate.opsForValue().set(productKey, seckillProduct, expireSeconds, TimeUnit.SECONDS);
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(seckillProduct.getSeckillStock()), expireSeconds, TimeUnit.SECONDS);

        return R.ok("预热成功，商品ID：" + id);
    }

    /**
     * 检查Redis中的数据
     */
    @GetMapping("/check/{id}")
    public R<String> checkRedisData(@PathVariable Long id) {
        String productKey = SECKILL_PRODUCT_KEY + id;
        String stockKey = SECKILL_STOCK_KEY + id;
        String userListKey = "seckill:users:" + id;

        StringBuilder result = new StringBuilder();
        result.append("=== Redis 数据检查 ===\n");

        Object obj = redisTemplate.opsForValue().get(productKey);
        SeckillProduct product = obj instanceof SeckillProduct ? (SeckillProduct) obj : null;
        if (product != null) {
            result.append("✓ 商品信息存在\n");
            result.append("  商品ID: ").append(product.getId()).append("\n");
            result.append("  秒杀价格: ").append(product.getSeckillPrice()).append("\n");
            result.append("  秒杀库存: ").append(product.getSeckillStock()).append("\n");
            result.append("  开始时间: ").append(product.getStartTime()).append("\n");
            result.append("  结束时间: ").append(product.getEndTime()).append("\n");
            result.append("  状态: ").append(product.getStatus()).append("\n");
        } else {
            result.append("✗ 商品信息不存在\n");
        }

        String stock = stringRedisTemplate.opsForValue().get(stockKey);
        if (stock != null) {
            result.append("✓ 库存存在: ").append(stock).append("\n");
        } else {
            result.append("✗ 库存不存在\n");
        }

        Long userCount = stringRedisTemplate.opsForSet().size(userListKey);
        if (userCount != null && userCount > 0) {
            result.append("✓ 已购买用户数: ").append(userCount).append("\n");
        } else {
            result.append("✗ 无已购买用户\n");
        }

        return R.ok(result.toString());
    }

    /**
     * 清除指定商品的Redis数据
     */
    @DeleteMapping("/clear/{id}")
    public R<String> clearRedisData(@PathVariable Long id) {
        String productKey = SECKILL_PRODUCT_KEY + id;
        String stockKey = SECKILL_STOCK_KEY + id;
        String userListKey = "seckill:users:" + id;

        boolean productDeleted = Boolean.TRUE.equals(redisTemplate.delete(productKey));
        boolean stockDeleted = Boolean.TRUE.equals(stringRedisTemplate.delete(stockKey));
        boolean usersDeleted = Boolean.TRUE.equals(stringRedisTemplate.delete(userListKey));

        StringBuilder result = new StringBuilder();
        result.append("清除结果:\n");
        result.append("商品信息: ").append(productDeleted ? "已清除" : "不存在").append("\n");
        result.append("库存: ").append(stockDeleted ? "已清除" : "不存在").append("\n");
        result.append("用户集合: ").append(usersDeleted ? "已清除" : "不存在").append("\n");

        return R.ok(result.toString());
    }

    /**
     * 清除所有秒杀相关的Redis数据
     */
    @DeleteMapping("/clearAll")
    public R<String> clearAllSeckillData() {
        Set<String> productKeys = redisTemplate.keys("seckillProduct:*");
        Set<String> stockKeys = stringRedisTemplate.keys("seckill:stock:*");
        Set<String> userKeys = stringRedisTemplate.keys("seckill:users:*");

        int count = 0;
        if (productKeys != null) {
            for (String key : productKeys) {
                if (Boolean.TRUE.equals(redisTemplate.delete(key))) count++;
            }
        }
        if (stockKeys != null) {
            for (String key : stockKeys) {
                if (Boolean.TRUE.equals(stringRedisTemplate.delete(key))) count++;
            }
        }
        if (userKeys != null) {
            for (String key : userKeys) {
                if (Boolean.TRUE.equals(stringRedisTemplate.delete(key))) count++;
            }
        }

        return R.ok("已清除 " + count + " 个Redis key");
    }

    /**
     * 查看所有秒杀相关的Key
     */
    @GetMapping("/keys")
    public R<String> getAllSeckillKeys() {
        StringBuilder result = new StringBuilder();
        result.append("=== 所有秒杀相关的Key ===\n");

        Set<String> productKeysSet = redisTemplate.keys("seckillProduct:*");
        if (productKeysSet != null && !productKeysSet.isEmpty()) {
            result.append("商品信息Keys: ").append(String.join(", ", productKeysSet)).append("\n");
        } else {
            result.append("无商品信息Keys\n");
        }

        Set<String> stockKeysSet = stringRedisTemplate.keys("seckill:stock:*");
        if (stockKeysSet != null && !stockKeysSet.isEmpty()) {
            result.append("库存Keys: ").append(String.join(", ", stockKeysSet)).append("\n");
        } else {
            result.append("无库存Keys\n");
        }

        Set<String> userKeysSet = stringRedisTemplate.keys("seckill:users:*");
        if (userKeysSet != null && !userKeysSet.isEmpty()) {
            result.append("用户Keys: ").append(String.join(", ", userKeysSet)).append("\n");
        } else {
            result.append("无用户Keys\n");
        }

        return R.ok(result.toString());
    }
}
