package com.example.sell.scheduler;

import com.example.sell.dao.SeckillMessageMapper;
import com.example.sell.dao.SeckillProductMapper;
import com.example.sell.entity.SeckillMessage;
import com.example.sell.entity.SeckillProduct;
import com.example.sell.service.impl.RocketMQMessageService;
import com.example.sell.service.impl.SeckillIdempotentService;
import com.example.sell.service.impl.SeckillRollbackService;
import com.example.sell.service.impl.AlertService;
import com.example.sell.utils.DistributedLockUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀库存对账补偿任务（最后一道防线）
 * <p>
 * 解决的核心问题：
 * Lua 脚本在 Redis 中原子扣减库存成功，但后续流程（MySQL 持久化、MQ 发送）全部失败，
 * 且 sendToMq 中 Redis 回滚也失败的极端情况。
 * <p>
 * 工作原理：
 * 1. Lua 脚本扣库存时，原子地将 userId LPUSH 到 seckill:pending:{seckillProductId} 队列
 * 2. sendToMq 成功后，会 LREM 清理这个条目
 * 3. 本定时任务扫描 pending 队列中残留的条目（即"扣了库存但没有成功发消息"的用户）
 * 4. 对残留条目：检查 MySQL 消息表是否有对应记录
 *    - 有记录 → 说明 MySQL 侧已处理，仅清理 pending 条目
 *    - 无记录 → 说明整个流程中断了，创建 MySQL 消息记录并触发 MQ 重发
 * <p>
 * 调度频率：每 2 分钟执行一次
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class SeckillStockReconciliationTask {

    private static final String SECKILL_PENDING_KEY_PREFIX = "seckill:pending:";
    private static final int BATCH_SIZE = 100;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillMessageMapper seckillMessageMapper;

    @Resource
    private SeckillProductMapper seckillProductMapper;

    @Resource
    private RocketMQMessageService rocketMQMessageService;


    @Resource
    private DistributedLockUtil distributedLockUtil;

    @Resource
    private AlertService alertService;

    @Resource
    private SeckillRollbackService seckillRollbackService;

    /**
     * 对账补偿主任务
     * 每 2 分钟执行一次，扫描 Redis pending 队列中的残留条目
     */
    @Scheduled(cron = "0 */2 * * * ?")
    public void reconcilePendingOrders() {
        String lockKey = "seckill:reconciliation:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 110, TimeUnit.SECONDS);
        if (lockValue == null) {
            return; // 其他实例正在处理
        }

        try {
            log.debug("【库存对账】开始扫描 pending 队列...");

            // 只获取活跃的秒杀商品（未结束或近 24h 内结束的），避免扫描全量数据
            List<SeckillProduct> activeProducts = seckillProductMapper.findActiveProducts();
            if (activeProducts == null || activeProducts.isEmpty()) {
                return;
            }

            int totalProcessed = 0;
            int totalRecovered = 0;

            for (SeckillProduct product : activeProducts) {
                Long seckillProductId = product.getId();
                String pendingKey = SECKILL_PENDING_KEY_PREFIX + seckillProductId;

                // 分批获取 pending 队列中的条目，避免一次性取出大量数据导致 Redis 阻塞
                int start = 0;
                while (true) {
                    List<String> batchUserIds = stringRedisTemplate.opsForList().range(
                            pendingKey, start, start + BATCH_SIZE - 1);
                    if (batchUserIds == null || batchUserIds.isEmpty()) {
                        break;
                    }

                    log.info("【库存对账】秒杀商品 ID: {}，处理第 {}-{} 条，共 {} 条", 
                            seckillProductId, start + 1, start + batchUserIds.size(), 
                            stringRedisTemplate.opsForList().size(pendingKey));

                    for (String userIdStr : batchUserIds) {
                        try {
                            Long userId = Long.parseLong(userIdStr);
                            boolean recovered = reconcileOneEntry(userId, seckillProductId);
                            totalProcessed++;
                            if (recovered) {
                                totalRecovered++;
                            }
                        } catch (NumberFormatException e) {
                            log.warn("【库存对账】pending 队列数据格式异常：{}", userIdStr);
                            // 清理无效数据
                            stringRedisTemplate.opsForList().remove(pendingKey, 1, userIdStr);
                        } catch (Exception e) {
                            log.error("【库存对账】处理 pending 条目异常，用户：{}, 商品：{}", 
                                    userIdStr, seckillProductId, e);
                        }
                    }
                    
                    start += BATCH_SIZE;
                    
                    // 每批处理后短暂休眠，避免对 Redis 造成过大压力
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("【库存对账】批处理被中断");
                        break;
                    }
                }
            }

            if (totalProcessed > 0) {
                log.info("【库存对账】扫描完成，处理：{} 条，恢复/补发：{} 条", totalProcessed, totalRecovered);
            }

        } catch (Exception e) {
            log.error("【库存对账】对账任务异常", e);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 对账单条 pending 记录
     * <p>
     * 判断策略：
     * 1. MySQL 消息已消费成功（订单已生成）→ 仅清理 pending
     * 2. MySQL 消息存在但未消费成功（处理中/待重试）→ 仅清理 pending，由消息补偿任务负责
     * 3. MySQL 无记录 → 尝试补发消息，失败则回滚 Redis 库存
     * </p>
     *
     * @return true=执行了恢复或补发操作
     */
    private boolean reconcileOneEntry(Long userId, Long seckillProductId) {
        String pendingKey = SECKILL_PENDING_KEY_PREFIX + seckillProductId;

        // 策略一：检查是否已消费成功（订单已生成）
        int consumeSuccessCount = seckillMessageMapper.countConsumeSuccess(userId, seckillProductId);
        if (consumeSuccessCount > 0) {
            // 订单已生成，pending 只是没被清理
            stringRedisTemplate.opsForList().remove(pendingKey, 1, String.valueOf(userId));
            log.debug("【库存对账】订单已生成，清理 pending，用户 ID: {}, 商品 ID: {}", userId, seckillProductId);
            return false;
        }


        // 策略二：检查 MySQL 消息表是否有记录（任何状态）
        SeckillMessage existingMsg = seckillMessageMapper.findByUserAndProduct(userId, seckillProductId);
        if (existingMsg != null) {
            // MySQL 有记录，说明 reliableSend 成功了，消息正在处理中
            // 清理 pending，后续由 SeckillMessageCompensationTask 负责消息重发
            stringRedisTemplate.opsForList().remove(pendingKey, 1, String.valueOf(userId));
            log.debug("【库存对账】MySQL 已有消息 (status={}, consumeStatus={})，清理 pending，用户 ID: {}, 商品 ID: {}",
                    existingMsg.getStatus(), existingMsg.getConsumeStatus(), userId, seckillProductId);
            return false;
        }

        // 策略三：MySQL 中无记录 → 说明：Redis 扣了库存，但 reliableSend 失败且回滚也失败
        // 尝试补发消息（创建 MySQL 记录 + 发 MQ），如果补发也失败则回滚 Redis 库存
        log.warn("【库存对账】发现孤立扣减！用户 ID: {}, 商品 ID: {}，尝试补发消息", userId, seckillProductId);

        try {
            // messageId 级别幂等，每次 reliableSend 生成新 messageId，无需清理旧标记
            // 重新发送可靠消息
            String messageId = rocketMQMessageService.reliableSend(userId, seckillProductId);
            if (messageId != null) {
                // 补发成功，清理 pending
                stringRedisTemplate.opsForList().remove(pendingKey, 1, String.valueOf(userId));
                log.info("【库存对账】补发消息成功，消息 ID: {}, 用户 ID: {}, 商品 ID: {}",
                        messageId, userId, seckillProductId);
                return true;
            } else {
                // 幂等检查认为已发送（可能另一个线程已处理），清理 pending
                stringRedisTemplate.opsForList().remove(pendingKey, 1, String.valueOf(userId));
                log.info("【库存对账】消息已被其他线程处理，用户 ID: {}, 商品 ID: {}", userId, seckillProductId);
                return false;
            }
        } catch (Exception e) {
            // 补发也失败了 → 原子回滚 Redis 库存
            log.error("【库存对账】补发失败，原子回滚 Redis 库存，用户 ID: {}, 商品 ID: {}", userId, seckillProductId, e);
            boolean rollbackSuccess = seckillRollbackService.rollback(userId, seckillProductId);
            if (rollbackSuccess) {
                log.warn("【库存对账】Redis 库存已原子回滚，用户 ID: {}, 商品 ID: {}", userId, seckillProductId);
                return true;
            } else {
                log.error("【库存对账】Redis 库存回滚也失败！需要人工介入，用户 ID: {}, 商品 ID: {}",
                        userId, seckillProductId);
                // 触发告警通知
                alertService.alertStockAnomaly(seckillProductId, userId,
                        "Redis 库存回滚失败，补发消息也失败，需人工介入");
                return false;
            }
        }
    }
}
