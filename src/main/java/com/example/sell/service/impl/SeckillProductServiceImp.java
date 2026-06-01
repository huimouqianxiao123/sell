package com.example.sell.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.sell.Inceptor.RateLimitInterceptor;
import com.example.sell.common.UserContext;
import com.example.sell.common.UserInfo;
import com.example.sell.config.LuaScripts;
import com.example.sell.dao.OrderMapper;
import com.example.sell.dao.ProductMapper;
import com.example.sell.dao.SeckillMessageMapper;
import com.example.sell.dao.SeckillProductMapper;
import com.example.sell.dto.SeckillProductRequest;
import com.example.sell.entity.Product;
import com.example.sell.entity.SeckillProduct;
import com.example.sell.vo.SeckillProductDetailVo;
import com.example.sell.service.SeckillProductService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author 屈轩
 */
@Slf4j
@Service
public class SeckillProductServiceImp extends ServiceImpl<SeckillProductMapper, SeckillProduct>
        implements SeckillProductService {
    @Resource
    private ProductMapper productMapper;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private LuaScripts luaScripts;
    @Resource
    private SeckillProductMapper seckillProductMapper;
    @Resource(name = "ioTaskExecutor")
    private ThreadPoolTaskExecutor ioTaskExecutor;
    @Resource
    private RocketMQMessageService rocketMQMessageService;
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private SeckillIdempotentService seckillIdempotentService;
    @Resource
    private SeckillMessageMapper seckillMessageMapper;
    @Resource
    private SeckillRollbackService seckillRollbackService;
    private static final String PRODUCT_KEY = "product:";
    private static final String SECKILL_PRODUCT_KEY = "seckillProduct:";
    private static final String SECKILL_PRODUCT_LIST_KEY = "seckillProduct:list";
    private static final String SECKILL_PENDING_KEY = "seckill:pending:";
    private static final String SECKILL_STOCK_KEY = "seckill:stock:";
    private static final String SECKILL_STOCK_VERSION_KEY = "seckill:stock:version:";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillProduct(SeckillProductRequest seckillProductRequest) {
        if (seckillProductRequest == null) {
            throw new RuntimeException("参数错误");
        }
        Long productId = seckillProductRequest.getProductId();
        if (productId == null) {
            throw new RuntimeException("参数错误");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }
        if (product.getStatus() != 1) {
            throw new RuntimeException("商品未上架");
        }
        Integer stock = seckillProductRequest.getSeckillStock();
        if (stock <= 0) {
            throw new RuntimeException("秒杀库存不能小于0");
        }
        if (product.getStock() <= 0 || product.getStock() < stock) {
            throw new RuntimeException("商品库存不足");
        }
        SeckillProduct seckillProduct = SeckillProduct.builder()
                .productId(productId)
                .seckillPrice(seckillProductRequest.getSeckillPrice())
                .seckillStock(seckillProductRequest.getSeckillStock())
                .startTime(seckillProductRequest.getStartTime())
                .endTime(seckillProductRequest.getEndTime())
                .status(0)
                .build();
        // 乐观锁扣减库存，重试最多3次应对普通订单并发扣减导致的版本冲突
        int rows = 0;
        for (int retry = 0; retry < 3; retry++) {
            Product latestProduct = productMapper.selectById(productId);
            if (latestProduct == null || latestProduct.getStock() < stock) {
                throw new RuntimeException("商品库存不足");
            }
            LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.setSql("stock = stock - " + stock + ", version = version + 1");
            updateWrapper.eq(Product::getId, productId);
            updateWrapper.eq(Product::getVersion, latestProduct.getVersion());
            rows = productMapper.update(null, updateWrapper);
            if (rows > 0) {
                break;
            }
            // 版本冲突，短暂等待后重试
            if (retry < 2) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("操作被中断");
                }
            }
        }
        if (rows == 0) {
            throw new RuntimeException("加入秒杀库存失败，请重试");
        }
        this.save(seckillProduct);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 清理相关缓存
                redisTemplate.delete(PRODUCT_KEY + productId);
                redisTemplate.delete(SECKILL_PRODUCT_LIST_KEY);

                // 缓存秒杀商品信息（统一过期时间策略）
                String seckillProductKey = SECKILL_PRODUCT_KEY + seckillProduct.getId();
                long expireSeconds = calculateExpireSeconds(seckillProduct);
                redisTemplate.opsForValue().set(seckillProductKey, seckillProduct, expireSeconds, TimeUnit.SECONDS);

                // 异步预热库存到Redis（统一库存计算逻辑）
                ioTaskExecutor.execute(() -> warmUpSeckillStock(seckillProduct.getId()));
            }
        });
    }

    /**
     * <p>
     * lua脚本的秒杀接口
     * </p>
     *
     * @param id 秒杀商品ID
     * @return 秒杀结果
     */
    @Override
    public String startSeckill(Long id) {
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();

        // 请求幂等性检查：防止短时间内重复点击，避免多次执行Redis和MQ操作
        if (!seckillIdempotentService.checkAndSetIdempotent(userId, id)) {
            return "失败：请勿重复点击，请稍后再试";
        }

        try {
            return doSeckill(userId, id);
        } catch (Exception e) {
            // 发生异常时，判断是否需要清理幂等标记允许重试
            handleExceptionAndCleanIdempotent(userId, id, e);
            throw e;
        }
    }

    /**
     * 处理异常并根据异常类型决定是否清理幂等标记
     * 业务失败（如售罄、重复购买）不清理，系统异常（如网络、缓存问题）才清理
     */
    private void handleExceptionAndCleanIdempotent(Long userId, Long id, Exception e) {
        String message = e.getMessage();
        if (message == null) {
            seckillIdempotentService.removeIdempotent(userId, id);
            return;
        }
        // 以下业务失败情况不清理幂等标记，避免用户重复点击
        if (message.contains("商品已售罄") 
                || message.contains("您已经购买过")
                || message.contains("秒杀活动未开始")
                || message.contains("秒杀活动已结束")) {
            // 业务明确失败，不清理幂等标记
            log.debug("【秒杀】业务失败，保留幂等标记，用户ID: {}, 商品ID: {}, 原因: {}", userId, id, message);
            return;
        }
        // 系统异常，清理幂等标记允许用户重试
        seckillIdempotentService.removeIdempotent(userId, id);
    }

    /**
     * 秒杀核心逻辑（从startSeckill中提取，便于幂等控制）
     * 注意：幂等标记的清理统一由 handleExceptionAndCleanIdempotent 方法处理，
     *       本方法内部不再调用 removeIdempotent，避免逻辑混乱。
     */
    private String doSeckill(Long userId, Long id) {
        String seckillProductKey = SECKILL_PRODUCT_KEY + id;
        SeckillProduct seckillProduct = (SeckillProduct) redisTemplate.opsForValue().get(seckillProductKey);

        if (seckillProduct == null) {
            // 商品不存在，属于系统异常，由调用方清理幂等标记
            throw new RuntimeException("秒杀商品不存在或未预热");
        }

        LocalDateTime now = LocalDateTime.now();
        if (seckillProduct.getStartTime() != null && now.isBefore(seckillProduct.getStartTime())) {
            // 活动未开始，属于业务失败，不清理幂等标记
            throw new RuntimeException("秒杀活动未开始");
        }

        if (seckillProduct.getEndTime() != null && now.isAfter(seckillProduct.getEndTime())) {
            // 活动已结束，属于业务失败，不清理幂等标记
            throw new RuntimeException("秒杀活动已结束");
        }

        String stockKey = "seckill:stock:" + id;
        String userListKey = "seckill:users:" + id;
        String pendingKey = SECKILL_PENDING_KEY + id;
        String stockStr = stringRedisTemplate.opsForValue().get(stockKey);
        // 库存缓存不存在时，直接返回系统繁忙，异步触发预热恢复
        // 简化逻辑：不在秒杀请求链路中做DB查询和复杂恢复，由异步预热任务统一处理
        if (stockStr == null) {
            ioTaskExecutor.execute(() -> warmUpSeckillStock(id));
            // 系统繁忙，属于系统异常，由调用方清理幂等标记
            throw new RuntimeException("系统繁忙，请稍后重试");
        }

        Long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                luaScripts.getSeckill(),
                RScript.ReturnType.INTEGER,
                Arrays.<Object>asList(stockKey, userListKey, pendingKey),
            String.valueOf(userId), String.valueOf(calculateExpireSeconds(seckillProduct)));

        if (result == -1) {
            // 重复购买，业务失败，不清理幂等标记
            return "失败：您已经购买过，不能重复秒杀";
        } else if (result == 0) {
            RateLimitInterceptor.markSoldOut(id);
            // 商品已售罄，业务失败，不清理幂等标记
            return "失败：商品已售罄";
        } else if (result == 1) {
            // Lua已原子地：扣库存 + 记录用户 + 写入pending队列
            // 下面尝试持久化到MySQL并发送MQ，失败则回滚Redis
            boolean success = sendToMq(userId, id);
            if (success) {
                return "成功：排队中，正在生成订单...";
            } else {
                // 已回滚Redis库存，系统异常，由调用方清理幂等标记
                throw new RuntimeException("系统繁忙，请稍后重试");
            }
        }
        return "异常错误";
    }

    @Override
    public String getOrderNo() {
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();
        String seckillProductOrderNoKey = "seckill:product:order:no:" + userId;
        String orderNo = stringRedisTemplate.opsForValue().get(seckillProductOrderNoKey);
        return orderNo;
    }

    @Override
    public List<SeckillProductDetailVo> getSeckillProductList() {
        List<SeckillProductDetailVo> seckillProductList = (List<SeckillProductDetailVo>) redisTemplate
                .opsForValue().get(SECKILL_PRODUCT_LIST_KEY);
        if (seckillProductList != null) {
            return seckillProductList;
        }
        seckillProductList = seckillProductMapper.getSeckillProduct();
        redisTemplate.opsForValue().set(SECKILL_PRODUCT_LIST_KEY, seckillProductList, 600, TimeUnit.SECONDS);
        return seckillProductList;
    }

    /**
     * 查询秒杀订单数量（用于压测统计）
     * <p>
     * 只统计秒杀订单（order_type = 2），避免将普通订单计入
     * </p>
     *
     * @param productId 秒杀商品ID
     * @return 包含订单数量、Redis库存、已购买用户数等信息
     */
    @Override
    public Map<String, Object> getSeckillOrderCount(Long productId) {
        Map<String, Object> result = new HashMap<>();

        // 查询秒杀商品信息
        SeckillProduct seckillProduct = seckillProductMapper.selectById(productId);
        if (seckillProduct == null) {
            result.put("error", "秒杀商品不存在");
            result.put("count", 0);
            return result;
        }

        Long realProductId = seckillProduct.getProductId();
        Integer originalStock = seckillProduct.getSeckillStock();

        // 查询 MySQL 中的秒杀订单数量（只统计 order_type = 2 的订单）
        int seckillOrderCount = orderMapper.countSeckillOrderByProduct(realProductId);

        // 查询 Redis 中的剩余库存
        String stockKey = "seckill:stock:" + productId;
        String currentStockStr = stringRedisTemplate.opsForValue().get(stockKey);
        Integer currentStock = currentStockStr != null ? Integer.parseInt(currentStockStr) : null;

        // 查询 Redis 中已购买用户数（Set 大小）
        String userListKey = "seckill:users:" + productId;
        Long setSize = stringRedisTemplate.opsForSet().size(userListKey);
        int purchasedUserCount = setSize != null ? setSize.intValue() : 0;

        // 计算已售数量（根据Redis库存变化）
        Integer soldCount = null;
        if (currentStock != null && originalStock != null) {
            soldCount = originalStock - currentStock;
        }

        result.put("count", seckillOrderCount);
        result.put("orderCount", seckillOrderCount);
        result.put("seckillProductId", productId);
        result.put("productId", realProductId);
        result.put("originalStock", originalStock);
        result.put("currentStock", currentStock);
        result.put("soldCount", soldCount);
        result.put("purchasedUserCount", purchasedUserCount);

        return result;
    }

    // ==================== 统一库存预热与计算 ====================

    /**
     * 统一计算缓存过期时间（预热和兜底共用同一策略）
     * 策略：endTime + 24小时，若无endTime则默认24小时
     */
    @Override
    public long calculateExpireSeconds(SeckillProduct seckillProduct) {
        LocalDateTime now = LocalDateTime.now();
        if (seckillProduct.getEndTime() == null) {
            return TimeUnit.HOURS.toSeconds(24);
        }
        long seconds = ChronoUnit.SECONDS.between(now, seckillProduct.getEndTime().plusHours(24));
        return seconds > 0 ? seconds : TimeUnit.HOURS.toSeconds(24);
    }

    /**
     * 获取秒杀商品的实际剩余库存（用于Redis预热）
     * <p>
     * 计算公式：DB.seckillStock - 在途消息数
     * </p>
     * <p>
     * 为什么不能直接用 DB.seckillStock？
     * 秒杀流程中，Lua脚本先原子扣减Redis库存，然后异步发MQ消息，MQ消费者创建订单时才扣减DB库存。
     * 因此存在「Redis已扣减但DB尚未扣减」的在途窗口期。
     * 若此时Redis缓存丢失后直接用DB值预热，会导致库存虚增 → 超卖。
     * </p>
     * <p>
     * 在途消息的定义（来自 seckill_message 表）：
     * - status IN (0, 1)：消息已创建或已发送
     * - consume_status IS NULL 或 0：尚未消费成功
     * - consume_status = 1 的消息 → DB已扣减（不算在途）
     * - consume_status = 2 的消息 → Redis已回滚（不算在途）
     * </p>
     *
     * @param seckillProduct 秒杀商品
     * @return 实际剩余库存（已扣除在途订单）
     */
    private int calculateRemainStock(SeckillProduct seckillProduct) {
        SeckillProduct dbProduct = seckillProductMapper.selectById(seckillProduct.getId());
        if (dbProduct == null) {
            return 0;
        }
        int dbStock = dbProduct.getSeckillStock();

        // 查询在途消息数：已扣减Redis库存但DB库存尚未扣减的订单
        int inFlightCount = seckillMessageMapper.countInFlightBySeckillProductId(seckillProduct.getId());
        int remaining = dbStock - inFlightCount;

        if (inFlightCount > 0) {
            log.info("【库存计算】商品 [{}] DB库存: {}, 在途订单: {}, 实际剩余: {}",
                    seckillProduct.getId(), dbStock, inFlightCount, Math.max(0, remaining));
        }

        return Math.max(0, remaining);
    }

    /**
     * 统一的库存预热方法（定时任务预热、addSeckillProduct预热、startSeckill兜底 共用）
     * <p>
     * 使用版本号(时间戳)原子性检查，防止旧数据覆盖新数据：
     * - 若 Redis 中已有更新版本的库存，则跳过本次写入
     * - 通过 Lua 脚本保证 "检查版本 + 写入库存" 的原子性
     * </p>
     * <p>
     * 兜底预热优化：
     * - 添加预热失败重试机制（最多3次）
     * - 检查秒杀商品状态，只预热活跃商品
     * - 预热失败后记录日志，不影响主流程
     * </p>
     *
     * @param seckillProductId 秒杀商品ID
     */
    @Override
    public void warmUpSeckillStock(Long seckillProductId) {
        warmUpSeckillStock(seckillProductId, 0);
    }

    /**
     * 带重试机制的库存预热方法
     *
     * @param seckillProductId 秒杀商品ID
     * @param retryCount       当前重试次数
     */
    private void warmUpSeckillStock(Long seckillProductId, int retryCount) {
        try {
            SeckillProduct dbProduct = seckillProductMapper.selectById(seckillProductId);
            if (dbProduct == null) {
                log.warn("【秒杀预热】商品不存在，ID: {}", seckillProductId);
                return;
            }

            // 检查商品状态，只预热活跃商品（status=0未开始 或 status=1进行中）
            if (dbProduct.getStatus() != 0 && dbProduct.getStatus() != 1) {
                log.debug("【秒杀预热】商品非活跃状态，跳过预热，商品ID: {}, 状态: {}", seckillProductId, dbProduct.getStatus());
                return;
            }

            // 检查活动是否已结束
            if (dbProduct.getEndTime() != null && LocalDateTime.now().isAfter(dbProduct.getEndTime())) {
                log.debug("【秒杀预热】活动已结束，跳过预热，商品ID: {}", seckillProductId);
                return;
            }
            String stockKey = SECKILL_STOCK_KEY + seckillProductId;
            String versionKey = SECKILL_STOCK_VERSION_KEY + seckillProductId;

            // 统一库存计算 & 统一过期时间
            int remainStock = calculateRemainStock(dbProduct);
            long expireSeconds = calculateExpireSeconds(dbProduct);
            long version = System.currentTimeMillis();

            // 原子性版本检查并设置库存，防止旧数据覆盖新数据
            Long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    luaScripts.getWarmUp(),
                    RScript.ReturnType.INTEGER,
                    Arrays.<Object>asList(stockKey, versionKey),
                    String.valueOf(remainStock),
                    String.valueOf(version),
                    String.valueOf(expireSeconds)
            );

            if (result != null && result == 1) {
                RateLimitInterceptor.clearSoldOut(seckillProductId);
                log.info("【秒杀预热】库存已加载，商品ID: {}, 剩余库存: {}, 版本: {}, 过期: {}s",
                        seckillProductId, remainStock, version, expireSeconds);
            } else {
                log.debug("【秒杀预热】已有更新版本，跳过写入，商品ID: {}", seckillProductId);
            }
        } catch (Exception e) {
            log.error("【秒杀预热】预热失败，商品ID: {}, 重试次数: {}", seckillProductId, retryCount, e);

            // 重试机制：最多重试3次，每次间隔递增
            if (retryCount < 3) {
                int delayMs = 1000 * (retryCount + 1);
                log.info("【秒杀预热】准备重试，商品ID: {}, 重试次数: {}, 延迟: {}ms", seckillProductId, retryCount + 1, delayMs);
                try {
                    Thread.sleep(delayMs);
                    warmUpSeckillStock(seckillProductId, retryCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("【秒杀预热】重试被中断，商品ID: {}", seckillProductId, ie);
                }
            } else {
                log.error("【秒杀预热】预热失败，已达最大重试次数，商品ID: {}", seckillProductId, e);
            }
        }
    }

    // ==================== MQ与回滚 ====================

    /**
     * 发送秒杀消息到MQ
     * <p>
     * 可靠性保障：
     * 1. reliableSend同步持久化MySQL + 异步发送MQ
     * 2. 若MySQL持久化成功 → 即使MQ发送失败，补偿任务也能兜底
     * 3. 若MySQL持久化失败 → 原子回滚Redis库存，允许用户重试
     * 4. Lua脚本中原子写入的pending队列，由对账任务最终兜底
     * </p>
     *
     * @return true=消息已可靠投递（MySQL有记录），false=投递失败已回滚Redis
     */
    private boolean sendToMq(Long userId, Long id) {
        try {
            String messageId = rocketMQMessageService.reliableSend(userId, id);
            if (messageId != null) {
                log.debug("【秒杀】可靠消息已发送，消息ID: {}, 用户ID: {}, 商品ID: {}", messageId, userId, id);
            } else {
                log.debug("【秒杀】消息已存在，跳过发送，用户ID: {}, 商品ID: {}", userId, id);
            }
            return true;
        } catch (Exception e) {
            log.error("【秒杀】消息发送异常，开始原子回滚Redis库存，用户ID: {}, 商品ID: {}", userId, id, e);
            seckillRollbackService.rollback(userId, id);
            return false;
        }
    }

}
