package com.example.sell.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sell.dao.SeckillProductMapper;
import com.example.sell.domain.pojo.SeckillProduct;
import com.example.sell.service.SeckillProductService;
import com.example.sell.utils.DistributedLockUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀任务调度器
 * 负责秒杀商品的预热、状态更新和清理工作
 * <p>
 * 预热和库存计算统一委托给 SeckillProductService.warmUpSeckillStock()，
 * 不在此处重复编写库存计算、过期时间、版本检查等逻辑
 * </p>
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class MiaoShaTask {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillProductMapper seckillProductMapper;

    @Resource
    private SeckillProductService seckillProductService;

    @Resource
    private DistributedLockUtil distributedLockUtil;

    private static final String SECKILL_PRODUCT_KEY = "seckillProduct:";
    private static final String SECKILL_STOCK_KEY = "seckill:stock:";
    private static final String SECKILL_STOCK_VERSION_KEY = "seckill:stock:version:";
    private static final String SECKILL_PRODUCT_LIST_KEY = "seckillProduct:list";

    /**
     * 秒杀商品预热任务
     * 每分钟执行一次，预热范围：
     * 1. 未开始(status=0)且开始时间在未来5分钟内的商品 —— 提前预热
     * 2. 进行中(status=1)但Redis库存缓存不存在的商品 —— 缓存过期兜底
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void prepareSeckillProduct() {
        long start = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime fiveMinutesLater = now.plusMinutes(5);
        // 安全分布式锁：使用DistributedLockUtil统一管理
        String lockKey = "seckill:warmup:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 30, TimeUnit.SECONDS);
        if (lockValue == null) {
            log.info("【秒杀预热】其他实例正在执行预热任务，跳过");
            return;
        }
        try {
            // 清理列表缓存，让下次查询时拉取最新数据
            redisTemplate.delete(SECKILL_PRODUCT_LIST_KEY);
            // ===== 1. 预热即将开始的商品（status=0，未来5分钟内开始）=====
            List<SeckillProduct> upcomingList = seckillProductMapper.selectList(
                    new LambdaQueryWrapper<SeckillProduct>()
                            .eq(SeckillProduct::getStatus, 0)
                            .ge(SeckillProduct::getStartTime, now)
                            .le(SeckillProduct::getStartTime, fiveMinutesLater)
            );
            // ===== 2. 预热进行中但缓存可能过期的商品（status=1）=====
            List<SeckillProduct> activeList = seckillProductMapper.selectList(
                    new LambdaQueryWrapper<SeckillProduct>()
                            .eq(SeckillProduct::getStatus, 1)
            );

            int upcomingCount = upcomingList.size();
            int activeCount = activeList.size();

            if (upcomingCount == 0 && activeCount == 0) {
                log.info("【秒杀预热】暂无需要预热的秒杀商品");
                return;
            }

            int warmUpCount = 0;

            // 预热即将开始的商品：缓存商品信息 + 库存
            for (SeckillProduct product : upcomingList) {
                warmUpProductInfoAndStock(product);
                warmUpCount++;
            }

            // 预热进行中的商品：仅检查库存缓存是否存在，不存在则恢复
            for (SeckillProduct product : activeList) {
                String stockKey = SECKILL_STOCK_KEY + product.getId();
                String existingStock = stringRedisTemplate.opsForValue().get(stockKey);
                if (existingStock == null) {
                    log.warn("【秒杀预热】进行中的商品 [{}] 库存缓存丢失，触发恢复", product.getId());
                    warmUpProductInfoAndStock(product);
                    warmUpCount++;
                }
            }

            long costTime = System.currentTimeMillis() - start;
            if (warmUpCount > 0) {
                log.info("【秒杀预热】预热完成，即将开始: {}, 进行中恢复: {}, 耗时: {}ms",
                        upcomingCount, warmUpCount - upcomingCount, costTime);
            }
        } catch (Exception e) {
            log.error("【秒杀预热】预热任务异常", e);
        } finally {
            // 安全释放锁：统一使用 DistributedLockUtil
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 预热单个秒杀商品的信息和库存到Redis
     * <p>
     * 商品信息缓存：直接写入（幂等操作，重复写入无副作用）
     * 库存缓存：统一委托给 SeckillProductService.warmUpSeckillStock()（带版本检查，防超卖）
     * </p>
     */
    private void warmUpProductInfoAndStock(SeckillProduct seckillProduct) {
        Long productId = seckillProduct.getId();
        String productKey = SECKILL_PRODUCT_KEY + productId;

        try {
            // 1. 缓存商品信息（统一过期时间策略）
            long expireSeconds = seckillProductService.calculateExpireSeconds(seckillProduct);
            redisTemplate.opsForValue().set(productKey, seckillProduct, expireSeconds, TimeUnit.SECONDS);

            // 2. 库存预热：统一调用 Service 的 warmUpSeckillStock
            //    内部会计算实际剩余库存（初始库存-已成交订单数），并用Lua脚本原子性写入+版本检查
            seckillProductService.warmUpSeckillStock(productId);
        } catch (Exception e) {
            log.error("【秒杀预热】商品 [{}] 预热失败", productId, e);
        }
    }

    /**
     * 更新秒杀活动状态
     * 每分钟执行一次，检查并更新秒杀活动状态
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void updateSeckillStatus() {
        String lockKey = "seckill:status:update:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 30, TimeUnit.SECONDS);
        if (lockValue == null) {
            log.info("【状态更新】其他实例正在执行状态更新任务，跳过");
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();

            // 1. 将未开始(0)状态更新为进行中(1)：开始时间已到且结束时间未到
            LambdaUpdateWrapper<SeckillProduct> startWrapper = new LambdaUpdateWrapper<>();
            startWrapper.eq(SeckillProduct::getStatus, 0)
                    .le(SeckillProduct::getStartTime, now)
                    .gt(SeckillProduct::getEndTime, now)
                    .set(SeckillProduct::getStatus, 1);
            int startedCount = seckillProductMapper.update(null, startWrapper);
            if (startedCount > 0) {
                log.info("【状态更新】{} 个秒杀活动已开始", startedCount);
            }

            // 2. 将进行中(1)状态更新为已售罄(3)：同时检查DB库存和Redis库存
            List<SeckillProduct> activeProducts = seckillProductMapper.selectList(
                    new LambdaQueryWrapper<SeckillProduct>()
                            .eq(SeckillProduct::getStatus, 1)
            );
            int soldOutCount = 0;
            for (SeckillProduct product : activeProducts) {
                // DB库存已为0则直接标记
                boolean dbSoldOut = product.getSeckillStock() != null && product.getSeckillStock() == 0;
                // Redis库存为0也应标记（DB可能因sync延迟未同步）
                boolean redisSoldOut = false;
                String stockKey = SECKILL_STOCK_KEY + product.getId();
                String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
                if (redisStockStr != null) {
                    redisSoldOut = Integer.parseInt(redisStockStr) <= 0;
                }
                if (dbSoldOut || redisSoldOut) {
                    LambdaUpdateWrapper<SeckillProduct> soldOutWrapper = new LambdaUpdateWrapper<>();
                    soldOutWrapper.eq(SeckillProduct::getId, product.getId())
                            .eq(SeckillProduct::getStatus, 1)
                            .set(SeckillProduct::getStatus, 3);
                    int rows = seckillProductMapper.update(null, soldOutWrapper);
                    if (rows > 0) {
                        soldOutCount++;
                    }
                }
            }
            if (soldOutCount > 0) {
                log.info("【状态更新】{} 个秒杀活动已售罄", soldOutCount);
            }

            // 3. 将进行中(1)状态更新为已结束(2)：结束时间已到
            LambdaUpdateWrapper<SeckillProduct> endWrapper = new LambdaUpdateWrapper<>();
            endWrapper.eq(SeckillProduct::getStatus, 1)
                    .le(SeckillProduct::getEndTime, now)
                    .set(SeckillProduct::getStatus, 2);
            int endedCount = seckillProductMapper.update(null, endWrapper);
            if (endedCount > 0) {
                log.info("【状态更新】{} 个秒杀活动已结束", endedCount);
            }

            // 4. 将已售罄(3)状态更新为已结束(2)：结束时间已到
            LambdaUpdateWrapper<SeckillProduct> soldOutEndedWrapper = new LambdaUpdateWrapper<>();
            soldOutEndedWrapper.eq(SeckillProduct::getStatus, 3)
                    .le(SeckillProduct::getEndTime, now)
                    .set(SeckillProduct::getStatus, 2);
            int soldOutEndedCount = seckillProductMapper.update(null, soldOutEndedWrapper);
            if (soldOutEndedCount > 0) {
                log.info("【状态更新】{} 个已售罄的秒杀活动已结束", soldOutEndedCount);
            }
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 清理已结束的秒杀活动缓存
     * 每小时执行一次，清理Redis中已结束活动的缓存
     */
    @Scheduled(cron = "0 0 */1 * * ?")
    public void cleanExpiredSeckillCache() {
        String lockKey = "seckill:cache:clean:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 60, TimeUnit.SECONDS);
        if (lockValue == null) {
            log.info("【缓存清理】其他实例正在执行缓存清理任务，跳过");
            return;
        }
        try {
            LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);

            // 查询已结束(2)且结束时间超过1小时的秒杀活动
            List<SeckillProduct> expiredList = seckillProductMapper.selectList(
                    new LambdaQueryWrapper<SeckillProduct>()
                            .eq(SeckillProduct::getStatus, 2)
                            .le(SeckillProduct::getEndTime, oneHourAgo)
            );

            if (expiredList.isEmpty()) {
                return;
            }

            int successCount = 0;
            for (SeckillProduct product : expiredList) {
                Long productId = product.getId();
                try {
                    // 清理商品信息、库存、库存版本号
                    redisTemplate.delete(SECKILL_PRODUCT_KEY + productId);
                    stringRedisTemplate.delete(SECKILL_STOCK_KEY + productId);
                    stringRedisTemplate.delete(SECKILL_STOCK_VERSION_KEY + productId);
                    successCount++;
                } catch (Exception e) {
                    log.error("【缓存清理】商品 [{}] 缓存清理失败", productId, e);
                }
            }

            if (successCount > 0) {
                log.info("【缓存清理】清理完成，共清理 {} 个商品缓存", successCount);
            }
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 同步已售数量到数据库（仅记录已确认的销量，不直接覆盖DB库存）
     * <p>
     * 改为"只减不增"策略：Redis库存 < DB库存时才同步，
     * 防止因Redis缓存恢复（预热重新加载）导致DB库存被回退
     * </p>
     * 每5分钟执行一次
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void syncStockToDatabase() {
        String lockKey = "seckill:stock:sync:lock";
        String lockValue = distributedLockUtil.tryLock(lockKey, 60, TimeUnit.SECONDS);
        if (lockValue == null) {
            log.info("【库存同步】其他实例正在执行库存同步任务，跳过");
            return;
        }
        try {
            // 查询进行中和已售罄的秒杀活动
            List<SeckillProduct> activeList = seckillProductMapper.selectList(
                    new LambdaQueryWrapper<SeckillProduct>()
                            .in(SeckillProduct::getStatus, 1, 3)
            );

            if (activeList.isEmpty()) {
                return;
            }

            int syncCount = 0;
            for (SeckillProduct product : activeList) {
                Long productId = product.getId();
                String stockKey = SECKILL_STOCK_KEY + productId;

                try {
                    String stockStr = stringRedisTemplate.opsForValue().get(stockKey);
                    if (stockStr == null) {
                        continue;
                    }

                    int redisStock = Integer.parseInt(stockStr);

                    SeckillProduct currentProduct = seckillProductMapper.selectById(productId);
                    if (currentProduct == null) {
                        continue;
                    }

                    // 只减不增：Redis库存 < DB库存时才同步，防止回退
                    if (redisStock > currentProduct.getSeckillStock()) {
                        // Redis库存 > DB库存，可能存在回滚失败导致的库存异常
                        log.warn("【库存同步】检测到库存异常：Redis库存({}) > DB库存({})，商品ID: {}，" +
                                        "可能存在Redis回滚失败导致的库存虚增，请检查对账任务",
                                redisStock, currentProduct.getSeckillStock(), productId);
                        continue;
                    }
                    if (redisStock == currentProduct.getSeckillStock()) {
                        continue;
                    }

                    // 使用乐观锁更新，最多重试3次
                    for (int i = 0; i < 3; i++) {
                        SeckillProduct latest = seckillProductMapper.selectById(productId);
                        if (latest == null || redisStock >= latest.getSeckillStock()) {
                            break;
                        }

                        LambdaUpdateWrapper<SeckillProduct> updateWrapper = new LambdaUpdateWrapper<>();
                        updateWrapper.eq(SeckillProduct::getId, productId)
                                .eq(SeckillProduct::getSeckillStock, latest.getSeckillStock())
                                .eq(SeckillProduct::getVersion, latest.getVersion())
                                .set(SeckillProduct::getSeckillStock, redisStock)
                                .set(SeckillProduct::getVersion, latest.getVersion() + 1);
                        int rows = seckillProductMapper.update(null, updateWrapper);

                        if (rows > 0) {
                            syncCount++;
                            log.info("【库存同步】商品 [{}] 同步成功，Redis: {}, 原DB: {}",
                                    productId, redisStock, latest.getSeckillStock());
                            break;
                        }
                        // 冲突，等待后重试
                        Thread.sleep(100);
                    }
                } catch (Exception e) {
                    log.error("【库存同步】商品 [{}] 同步失败", productId, e);
                }
            }

            if (syncCount > 0) {
                log.info("【库存同步】同步完成，共同步 {} 个商品", syncCount);
            }
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

}
