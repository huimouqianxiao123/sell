package com.example.sell.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁工具类（Redisson 实现）
 * <p>
 * 基于 Redisson RLock 实现，相比原 setIfAbsent + Lua 脚本方案，提供以下安全增强：
 * <ul>
 *   <li>可重入锁支持：同一线程可多次获取同一把锁</li>
 *   <li>锁持有者精确识别：基于 Redis 连接 ID + 线程 ID，杜绝误删其他实例/线程的锁</li>
 *   <li>自动释放机制：即使应用宕机，锁也会在 leaseTime 超时后自动释放</li>
 * </ul>
 * </p>
 * <p>
 * <strong>注意：</strong>
 * <ul>
 *   <li>本工具指定了 leaseTime，因此 <strong>不启用 Watchdog 自动续期</strong>。锁会在 leaseTime 后强制释放。</li>
 *   <li>如果业务执行时间可能超过 leaseTime，请设置足够大的过期时间，或考虑使用 Redisson 的无 leaseTime 模式启用 Watchdog。</li>
 * </ul>
 * </p>
 * <p>
 * 保持原有接口签名不变，调用方（定时任务等）无需修改代码。
 * </p>
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class DistributedLockUtil {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 尝试获取分布式锁（指定 leaseTime，不启用 Watchdog）
     * <p>
     * 使用 Redisson RLock 的 tryLock 方法，非阻塞式获取锁。
     * 指定 leaseTime 后锁会在到期后自动释放（不启用看门狗续期）。
     * </p>
     * <p>
     * <strong>关于 lockValue：</strong>
     * 返回的 lockValue 是一个 UUID，仅用于兼容原有接口签名。
     * Redisson 内部通过 Redis 连接 ID + 线程 ID 识别锁持有者，lockValue 实际不参与锁的校验。
     * 真正的持有者校验在 unlock 时通过 {@code lock.isHeldByCurrentThread()} 完成。
     * </p>
     *
     * @param lockKey    锁的 Key
     * @param expireTime 过期时间（leaseTime），锁到期后自动释放
     * @param timeUnit   时间单位
     * @return 锁标识字符串（UUID），仅用于兼容接口；获取失败返回 null
     */
    public String tryLock(String lockKey, long expireTime, TimeUnit timeUnit) {
        return tryLockWithLease(lockKey, 0, expireTime, timeUnit);
    }

    /**
     * 尝试获取分布式锁（启用 Watchdog 自动续期）
     * <p>
     * 使用 Redisson RLock 的 lock 方法，启用 Watchdog 自动续期机制。
     * Watchdog 会在锁即将过期时自动续期（默认每 10 秒续期一次），直到业务调用 unlock()。
     * </p>
     * <p>
     * <strong>重要说明：</strong>此方法不等待（waitTime=0），如果锁被其他线程持有，会立即返回 null。
     * 如需等待获取锁，请使用 {@link #tryLockWithWatchdog(String, long, TimeUnit)} 方法。
     * </p>
     * <p>
     * <strong>适用场景：</strong>
     * <ul>
     *   <li>任务执行时间不确定，可能超过固定 leaseTime</li>
     *   <li>希望避免因任务超时导致的并发问题</li>
     * </ul>
     * </p>
     * <p>
     * <strong>注意事项：</strong>
     * <ul>
     *   <li>如果应用宕机，锁会在 watchdogTimeout（默认 30 秒）后释放</li>
     *   <li>必须确保在 finally 块中调用 unlock()，否则锁会长期占用</li>
     * </ul>
     * </p>
     *
     * @param lockKey 锁的 Key
     * @return 锁标识字符串（UUID），仅用于兼容接口；获取失败返回 null
     */
    public String tryLockWithWatchdog(String lockKey) {
        return tryLockWithLease(lockKey, 0, -1, TimeUnit.SECONDS);
    }

    /**
     * 尝试获取分布式锁（启用 Watchdog 自动续期，支持等待）
     * <p>
     * 使用 Redisson RLock 的 lock 方法，启用 Watchdog 自动续期机制。
     * Watchdog 会在锁即将过期时自动续期（默认每 10 秒续期一次），直到业务调用 unlock()。
     * </p>
     * <p>
     * <strong>适用场景：</strong>
     * <ul>
     *   <li>任务执行时间不确定，可能超过固定 leaseTime</li>
     *   <li>希望避免因任务超时导致的并发问题</li>
     *   <li>需要等待锁释放的场景</li>
     * </ul>
     * </p>
     * <p>
     * <strong>注意事项：</strong>
     * <ul>
     *   <li>如果应用宕机，锁会在 watchdogTimeout（默认 30 秒）后释放</li>
     *   <li>必须确保在 finally 块中调用 unlock()，否则锁会长期占用</li>
     * </ul>
     * </p>
     *
     * @param lockKey  锁的 Key
     * @param waitTime 等待获取锁的最大时间
     * @param timeUnit 时间单位
     * @return 锁标识字符串（UUID），仅用于兼容接口；获取失败返回 null
     */
    public String tryLockWithWatchdog(String lockKey, long waitTime, TimeUnit timeUnit) {
        return tryLockWithLease(lockKey, waitTime, -1, timeUnit);
    }

    /**
     * 内部方法：统一的锁获取逻辑
     *
     * @param lockKey    锁的 Key
     * @param waitTime   等待时间（0 表示不等待）
     * @param leaseTime  租约时间（-1 表示启用 Watchdog）
     * @param timeUnit   时间单位
     * @return 锁标识字符串（UUID），仅用于兼容接口；获取失败返回 null
     */
    private String tryLockWithLease(String lockKey, long waitTime, long leaseTime, TimeUnit timeUnit) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean acquired;
            if (leaseTime == -1) {
                // 启用 Watchdog：使用 lock() 方法
                acquired = lock.tryLock(waitTime, -1, timeUnit);
            } else {
                // 不启用 Watchdog：指定 leaseTime
                acquired = lock.tryLock(waitTime, leaseTime, timeUnit);
            }
            if (acquired) {
                String lockValue = UUID.randomUUID().toString();
                String mode = leaseTime == -1 ? "Watchdog" : "Lease(" + leaseTime + " " + timeUnit + ")";
                log.debug("【分布式锁-Redisson】获取锁成功，key: {}, lockValue: {}, 模式: {}", lockKey, lockValue, mode);
                return lockValue;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("【分布式锁-Redisson】获取锁被中断，key: {}", lockKey);
        }
        log.debug("【分布式锁-Redisson】获取锁失败，key: {}", lockKey);
        return null;
    }

    /**
     * 安全释放分布式锁
     * <p>
     * Redisson 内置了持有者校验，只有锁的持有线程才能释放锁，
     * 其他线程/实例调用 unlock 会抛出 IllegalMonitorStateException。
     * 此方法内部做了异常捕获，确保不会影响业务逻辑。
     * </p>
     * <p>
     * <strong>关于 lockValue 参数：</strong>
     * 此参数仅用于兼容原有接口签名，实际不参与锁的校验。
     * 真正的持有者校验通过 {@code lock.isHeldByCurrentThread()} 完成。
     * </p>
     *
     * @param lockKey   锁的 Key
     * @param lockValue 获取锁时返回的标识字符串（实际未使用，保留用于兼容）
     */
    public void unlock(String lockKey, String lockValue) {
        if (lockValue == null) {
            return;
        }
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("【分布式锁-Redisson】释放锁成功，key: {}", lockKey);
            } else {
                log.debug("【分布式锁-Redisson】当前线程未持有该锁，跳过释放，key: {}", lockKey);
            }
        } catch (IllegalMonitorStateException e) {
            log.debug("【分布式锁-Redisson】锁已过期或被其他实例持有，跳过释放，key: {}", lockKey);
        } catch (Exception e) {
            log.warn("【分布式锁-Redisson】释放锁异常，key: {}", lockKey, e);
        }
    }
}
