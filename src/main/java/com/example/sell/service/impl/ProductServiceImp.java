package com.example.sell.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sell.ai.search.ElasticAiSearchService;
import com.example.sell.common.UserContext;
import com.example.sell.dao.ProductMapper;
import com.example.sell.dto.ProductRequest;
import com.example.sell.entity.Product;
import com.example.sell.service.ProductService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author 屈轩
 */
@Service
public class ProductServiceImp extends ServiceImpl<ProductMapper, Product> implements ProductService {


    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private ElasticAiSearchService elasticAiSearchService;

    private static final String PRODUCT_KEY = "product:";

    @Override
    public List<Product> listProducts() {
        return this.list();
    }

    @Override
    public Page<Product> pageProducts(Integer page, Integer size, String name) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? 10 : size;

        Page<Product> pageInfo = new Page<>(safePage, safeSize);
        LambdaQueryWrapper<Product> queryWrapper = new LambdaQueryWrapper<>();
        if (name != null && !name.isEmpty()) {
            queryWrapper.like(Product::getName, name);
        }
        queryWrapper.orderByDesc(Product::getCreateTime);
        return this.page(pageInfo, queryWrapper);
    }


    /**
     * 创建或更新商品
     * 注意：如果是更新操作，不会修改 createTime
     *
     * @param productRequest 商品请求对象
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOrUpdateProduct(ProductRequest productRequest) {
        if (productRequest == null) {
            throw new RuntimeException("商品信息不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        Product product;

        // 判断是新增还是更新
        if (productRequest.getId() == null) {
            // 新增商品：需要设置 createTime 和 updateTime
            product = Product.builder()
                    .name(productRequest.getName())
                    .price(productRequest.getPrice())
                    .stock(productRequest.getStock())
                    .description(productRequest.getDescription())
                    .image(productRequest.getImage())
                    .status(productRequest.getStatus())
                    .createTime(now)
                    .updateTime(now)
                    .build();
        } else {
            // 更新商品：只设置 updateTime，不设置 createTime
            // 从数据库查询原有商品信息获取 createTime
            Product existingProduct = this.getById(productRequest.getId());
            if (existingProduct == null) {
                throw new RuntimeException("商品不存在");
            }

            product = Product.builder()
                    .id(productRequest.getId())
                    .name(productRequest.getName())
                    .price(productRequest.getPrice())
                    .stock(productRequest.getStock())
                    .description(productRequest.getDescription())
                    .image(productRequest.getImage())
                    .status(productRequest.getStatus())
                    .createTime(existingProduct.getCreateTime()) // 保留原创建时间
                    .updateTime(now)
                    .build();
        }

        this.saveOrUpdate(product);

        // 使用事务同步器，在事务提交成功后删除Redis缓存
        // 这样可以保证：如果事务回滚，Redis不会被错误地删除
        if (product.getId() != null) {
            Long productId = product.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    String productKey = PRODUCT_KEY + productId;
                    redisTemplate.delete(productKey);
                    elasticAiSearchService.indexProduct(product);
                }
            });
        }
    }

    /**
     * 删除商品
     * 缓存更新策略说明：
     * 方案A（先删缓存 -> 再删DB）：风险极高。在删完缓存但还没删DB的瞬间，
     * 如果别的线程来读，会把DB里的旧数据又写回缓存，导致缓存里一直是脏数据。❌ 不推荐
     * <p>
     * 方案B（先删DB -> 再删缓存）：风险较低。
     * 只有在"删完DB还没来得及删缓存"的极短几毫秒内，用户会读到旧数据，但随后缓存就被删除了。✅ 推荐 (通用方案)
     *
     * @param id 商品ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProductById(Long id) {
        if (id == null) {
            throw new RuntimeException("商品ID不能为空");
        }
        this.removeById(id);

        // 使用事务同步器，在事务提交成功后删除Redis缓存
        // 这样可以保证：如果事务回滚，Redis不会被错误地删除
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String productKey = PRODUCT_KEY + id;
                redisTemplate.delete(productKey);
                elasticAiSearchService.deleteProduct(id);
            }
        });
    }

    // 缓存空值的标记对象，用于防止缓存穿透
    private static final Product NULL_PRODUCT = new Product();

    /***
     * 这里查询商品时，先从Redis中查询，如果Redis中没有，则从数据库中查询，并保存到Redis中
     * 使用空值缓存策略防止缓存穿透
     *
     * @param id 商品ID
     * @return 商品对象
     */
    @Override
    public Product getProductById(Long id) {
        if (id == null) {
            throw new RuntimeException("商品ID不能为空");
        }
        String productKey = PRODUCT_KEY + id;
        Object cachedValue = redisTemplate.opsForValue().get(productKey);

        // 检查是否是空值标记（防止缓存穿透）
        if (cachedValue == NULL_PRODUCT) {
            throw new RuntimeException("商品不存在");
        }

        // 如果是真实商品数据，直接返回
        if (cachedValue instanceof Product) {
            return (Product) cachedValue;
        }

        // Redis中没有，从数据库查询
        Product product = this.getById(id);
        if (product != null) {
            redisTemplate.opsForValue().set(productKey, product, 600, TimeUnit.SECONDS);
        } else {
            // 对于不存在的商品，在缓存中设置空值标记，防止缓存穿透，但时间较短
            redisTemplate.opsForValue().set(productKey, NULL_PRODUCT, 60, TimeUnit.SECONDS);
            throw new RuntimeException("商品不存在");
        }

        return product;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProduct(ProductRequest productRequest) {
        Long id = productRequest.getId();
        if (id == null) {
            throw new RuntimeException("商品ID不能为空");
        }

        // 先查询现有商品，获取 version 和其他字段
        Product existingProduct = this.getById(id);
        if (existingProduct == null) {
            throw new RuntimeException("商品不存在");
        }

        // 使用乐观锁更新：传入当前的 version，MyBatis-Plus 会自动处理 version + 1
        Product product = Product.builder()
                .id(id)
                .name(productRequest.getName())
                .stock(productRequest.getStock())
                .updateTime(LocalDateTime.now())
                .status(productRequest.getStatus())
                .image(productRequest.getImage())
                .description(productRequest.getDescription())
                .price(productRequest.getPrice())
                .version(existingProduct.getVersion()) // 设置当前版本号，启用乐观锁
                .build();

        boolean updated = this.updateById(product);
        if (!updated) {
            throw new RuntimeException("商品已被其他用户修改，请刷新后重试");
        }

        // 使用事务同步器，在事务提交成功后删除Redis缓存
        // 这样可以保证：如果事务回滚，Redis不会被错误地删除
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String productKey = PRODUCT_KEY + id;
                redisTemplate.delete(productKey);
                elasticAiSearchService.indexProduct(product);
            }
        });
    }

}
