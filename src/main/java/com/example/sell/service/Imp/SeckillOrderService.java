package com.example.sell.service.Imp;

import cn.hutool.core.lang.UUID;
import com.example.sell.dao.OrderItemMapper;
import com.example.sell.dao.OrderMapper;
import com.example.sell.dao.ProductMapper;
import com.example.sell.dao.SeckillProductMapper;
import com.example.sell.domain.enums.OrderStatusEnum;
import com.example.sell.domain.pojo.OrderItem;
import com.example.sell.domain.pojo.Orders;
import com.example.sell.domain.pojo.Product;
import com.example.sell.domain.pojo.SeckillProduct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 秒杀订单服务
 * <p>
 * 从 MiaoShaOrderConsumer 中提取出来，解决 this 调用导致 @Transactional 不生效的问题。
 * Spring AOP 代理要求事务方法必须通过代理对象调用，而非 this 直接调用。
 * </p>
 *
 * @author 屈轩
 */
@Slf4j
@Service
public class SeckillOrderService {

    @Resource
    private SeckillProductMapper seckillProductMapper;
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private OrderItemMapper orderItemMapper;
    @Resource
    private ProductMapper productMapper;

    /**
     * 处理秒杀订单创建（事务方法）
     * <p>
     * 事务保证以下操作的原子性：
     * 1. MySQL库存扣减（stock > 0 条件防超卖）
     * 2. 创建 Orders 订单
     * 3. 创建 OrderItem 订单项
     * 任何一步失败都会回滚
     * </p>
     *
     * @param userId           用户ID
     * @param seckillProductId 秒杀商品ID
     * @param productId        原商品ID（可选，为null时从秒杀商品中获取）
     * @return 订单号
     */
    @Transactional(rollbackFor = Exception.class)
    public String processOrder(Long userId, Long seckillProductId, Long productId) {
        SeckillProduct seckillProduct = seckillProductMapper.selectById(seckillProductId);
        if (seckillProduct == null) {
            throw new RuntimeException("秒杀商品不存在，ID: " + seckillProductId);
        }
        Long finalProductId = productId != null ? productId : seckillProduct.getProductId();
        String existingOrderNo = orderMapper.findLatestValidSeckillOrderNo(userId, finalProductId);
        if (existingOrderNo != null && !existingOrderNo.isEmpty()) {
            log.info("【秒杀订单】检测到重复消费，直接返回已有订单，用户ID: {}, 秒杀商品ID: {}, 订单号: {}",
                    userId, seckillProductId, existingOrderNo);
            return existingOrderNo;
        }
        // MySQL库存扣减（使用stock>0条件，不使用版本号）
        // 因为Redis Lua脚本已经保证了原子性扣减，这里只是同步MySQL
        int affectedRows = seckillProductMapper.decreaseStockWithoutVersion(seckillProductId);
        if (affectedRows == 0) {
            // 正常情况下不应该发生（Redis已验证），但作为最后防线
            throw new RuntimeException("MySQL库存扣减失败，库存不足，商品ID: " + seckillProductId);
        }
        log.info("【秒杀订单】MySQL库存扣减成功，商品ID: {}", seckillProductId);
        // 创建订单
        String orderNo = UUID.randomUUID().toString().replace("-", "");
        Orders order = Orders.builder()
                .orderNo(orderNo)
                .createTime(LocalDateTime.now())
                .totalAmount(seckillProduct.getSeckillPrice())
                .userId(userId)
                .status(OrderStatusEnum.PENDING_PAYMENT.getCode())
                .orderType(2)
                .build();
        orderMapper.insert(order);
        Long orderId = order.getId();
        // 创建订单项
        Product product = productMapper.selectById(finalProductId);
        if (product == null) {
            throw new RuntimeException("商品不存在，ID: " + finalProductId);
        }
        OrderItem orderItem = OrderItem
                .builder()
                .orderId(orderId)
                .orderNo(orderNo)
                .productId(finalProductId)
                .productName(product.getName())
                .productImage(product.getImage())
                .productDescription(product.getDescription())
                .unitPrice(seckillProduct.getSeckillPrice())
                .quantity(1).totalPrice(seckillProduct.getSeckillPrice())
                .build();
        orderItemMapper.insert(orderItem);
        return orderNo;
    }
}
