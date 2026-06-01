package com.example.sell.service.impl;

import com.example.sell.config.LuaScripts;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 秒杀库存回滚服务
 * <p>
 * 使用 Lua 脚本保证 INCR(恢复库存) + SREM(移除用户) + LREM(清理pending)
 * 三个操作的原子性，解决分步操作中途失败导致状态不一致的问题。
 * </p>
 * <p>
 * 原子性回滚的重要性：
 * - INCR 成功但 SREM 失败 → 库存恢复了但用户还在已购列表，无法重新秒杀
 * - SREM 成功但 INCR 失败 → 用户标记清了但库存没恢复，导致库存丢失
 * - Lua 脚本保证要么全部成功，要么全部不执行
 * </p>
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class SeckillRollbackService {

    private static final String SECKILL_STOCK_KEY = "seckill:stock:";
    private static final String SECKILL_USERS_KEY = "seckill:users:";
    private static final String SECKILL_PENDING_KEY = "seckill:pending:";

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private LuaScripts luaScripts;

    /**
     * 原子性回滚 Redis 库存
     * <p>
     * 通过 Lua 脚本一次性执行：
     * 1. 检查用户是否在已购列表（防止重复回滚导致库存虚增）
     * 2. INCR 恢复库存
     * 3. SREM 移除用户购买标记
     * 4. LREM 清理 pending 队列条目
     * </p>
     *
     * @param userId           用户ID
     * @param seckillProductId 秒杀商品ID
     * @return true=回滚成功, false=无需回滚或回滚失败
     */
    public boolean rollback(Long userId, Long seckillProductId) {
        try {
            String stockKey = SECKILL_STOCK_KEY + seckillProductId;
            String userListKey = SECKILL_USERS_KEY + seckillProductId;
            String pendingKey = SECKILL_PENDING_KEY + seckillProductId;

            Long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    luaScripts.getRollback(),
                    RScript.ReturnType.INTEGER,
                    Arrays.<Object>asList(stockKey, userListKey, pendingKey),
                    String.valueOf(userId));

            if (result != null && result == 1) {
                log.warn("【秒杀回滚】Redis库存已原子回滚，用户ID: {}, 商品ID: {}", userId, seckillProductId);
                return true;
            } else {
                log.debug("【秒杀回滚】用户不在已购列表，无需回滚，用户ID: {}, 商品ID: {}", userId, seckillProductId);
                return false;
            }
        } catch (Exception e) {
            log.error("【秒杀回滚】Redis库存回滚失败！对账补偿任务将兜底处理，用户ID: {}, 商品ID: {}",
                    userId, seckillProductId, e);
            return false;
        }
    }
}
