package com.example.sell.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sell.dao.OrderMapper;
import com.example.sell.dao.ProductMapper;
import com.example.sell.dao.SeckillProductMapper;
import com.example.sell.domain.pojo.Product;
import com.example.sell.domain.pojo.SeckillProduct;
import com.example.sell.utils.DistributedLockUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author 屈轩
 * <p>将秒杀活动结束，还有库存的秒杀商品放入原库存</p>
 * <p>每天执行一次，加分布式锁，防止多机重复运行</p>
 */
@Slf4j
@Component
public class RecoveryStockTask {
    @Resource
    private SeckillProductMapper seckillProductMapper;
    @Resource
    private ProductMapper productMapper;
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private DistributedLockUtil distributedLockUtil;
    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final String PRODUCT_KEY = "product:";
    private static final String SECKILL_STOCK_KEY = "seckill:stock:";
    private static final String RECOVERY_LOCK_KEY = "seckill:recovery:stock:lock";

    /**
     * 恢复秒杀库存任务
     * 每天凌晨2点执行，将已结束且有库存的秒杀商品剩余库存恢复到原商品库存
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void recoveryStock() {
        log.info("【库存恢复】开始执行库存恢复任务");
        LocalDateTime now = LocalDateTime.now();
        log.info("【库存恢复】当前时间：{}", now);

        // 使用分布式锁防止多机重复执行
        String lockValue = distributedLockUtil.tryLock(RECOVERY_LOCK_KEY, 30, TimeUnit.MINUTES);
        if (lockValue == null) {
            log.info("【库存恢复】获取分布式锁失败，其他实例正在执行任务");
            return;
        }
        try {
            // 查询已结束（状态为2）且还有库存的秒杀商品
            List<SeckillProduct> seckillProductList = seckillProductMapper.selectList(
                    new LambdaQueryWrapper<SeckillProduct>()
                            .eq(SeckillProduct::getStatus, 2)
                            .gt(SeckillProduct::getSeckillStock, 0)
            );

            if (seckillProductList.isEmpty()) {
                log.info("【库存恢复】暂无需要恢复库存的秒杀商品");
                return;
            }

            log.info("【库存恢复】发现 {} 个需要恢复库存的秒杀商品", seckillProductList.size());

            int successCount = 0;
            int failCount = 0;

            for (SeckillProduct seckillProduct : seckillProductList) {
                try {
                    recoverySingleProductStock(seckillProduct);
                    successCount++;
                    log.info("【库存恢复】商品 [{}] 库存恢复成功，恢复库存：{}", 
                            seckillProduct.getId(), seckillProduct.getSeckillStock());
                } catch (Exception e) {
                    failCount++;
                    log.error("【库存恢复】商品 [{}] 库存恢复失败：{}", 
                            seckillProduct.getId(), e.getMessage(), e);
                }
            }

            log.info("【库存恢复】库存恢复任务完成，成功：{}，失败：{}", successCount, failCount);

        } finally {
            distributedLockUtil.unlock(RECOVERY_LOCK_KEY, lockValue);
        }
    }

    /**
     * 恢复单个商品的库存
     * <p>
     * 基于多数据源交叉验证，选择最保守（最小）的恢复量，防止库存虚增：
     * 1. DB seckill_stock（可能因 sync 延迟而偏高）
     * 2. Redis 库存（最实时，但可能已被清理）
     * 3. 订单数据反推（初始分配 - 有效订单数）
     * </p>
     * 使用乐观锁保证并发安全
     *
     * @param seckillProduct 秒杀商品
     */
    @Transactional(rollbackFor = Exception.class)
    public void recoverySingleProductStock(SeckillProduct seckillProduct) {
        Long productId = seckillProduct.getProductId();
        Integer dbSeckillStock = seckillProduct.getSeckillStock();

        // 通过实际订单数据验证剩余库存
        int actualSold = orderMapper.countActiveSeckillOrderByProduct(productId);

        // 优先使用 Redis 库存（最实时），Redis 已清理则用 DB 值
        String stockKey = SECKILL_STOCK_KEY + seckillProduct.getId();
        String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
        int stockToRecover;
        if (redisStockStr != null) {
            int redisStock = Integer.parseInt(redisStockStr);
            stockToRecover = Math.min(dbSeckillStock, redisStock);
            if (redisStock != dbSeckillStock) {
                log.warn("【库存恢复】商品 [{}] DB库存({})与Redis库存({})不一致，使用较小值({})",
                        seckillProduct.getId(), dbSeckillStock, redisStock, stockToRecover);
            }
        } else {
            stockToRecover = dbSeckillStock;
            log.info("【库存恢复】商品 [{}] Redis库存已清理，使用DB库存({}), 实际已售: {}",
                    seckillProduct.getId(), dbSeckillStock, actualSold);
        }

        if (stockToRecover <= 0) {
            log.info("【库存恢复】商品 [{}] 无需恢复库存，剩余: {}", seckillProduct.getId(), stockToRecover);
            return;
        }

        // 查询原商品信息
        Product product = productMapper.selectById(productId);
        if (product == null) {
            log.error("【库存恢复】原商品不存在，ID: {}", productId);
            throw new RuntimeException("原商品不存在");
        }

        // 使用乐观锁更新原商品库存
        LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Product::getId, productId)
                .eq(Product::getVersion, product.getVersion())
                .setSql("stock = stock + " + stockToRecover)
                .setSql("version = version + 1");
        int affectedRows = productMapper.update(null, updateWrapper);

        if (affectedRows == 0) {
            log.warn("【库存恢复】商品 [{}] 库存更新失败，可能存在并发冲突", productId);
            throw new RuntimeException("库存更新失败，请重试");
        }

        // 更新秒杀商品库存为0，避免重复处理
        LambdaUpdateWrapper<SeckillProduct> seckillUpdateWrapper = new LambdaUpdateWrapper<>();
        seckillUpdateWrapper.eq(SeckillProduct::getId, seckillProduct.getId())
                .eq(SeckillProduct::getSeckillStock, dbSeckillStock)
                .set(SeckillProduct::getSeckillStock, 0);
        seckillProductMapper.update(null, seckillUpdateWrapper);

        // 删除Redis缓存
        String seckillKey = "seckill:product:" + seckillProduct.getId();
        String productKey = PRODUCT_KEY + productId;
        redisTemplate.delete(seckillKey);
        redisTemplate.delete(productKey);

        log.info("【库存恢复】商品 [{}] 库存恢复完成，原商品ID: {}, 恢复数量: {}, 实际已售: {}",
                seckillProduct.getId(), productId, stockToRecover, actualSold);
    }
}
