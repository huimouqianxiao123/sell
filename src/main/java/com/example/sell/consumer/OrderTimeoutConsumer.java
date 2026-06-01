package com.example.sell.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sell.dao.OrderItemMapper;
import com.example.sell.dao.OrderMapper;
import com.example.sell.dao.ProductMapper;
import com.example.sell.entity.OrderItem;
import com.example.sell.entity.Orders;
import com.example.sell.entity.Product;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author 屈轩
 * 订单超时取消消费者
 * 监听订单超时消息，自动取消超时未支付的订单
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq.listener", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
    topic = "order-timeout-topic",
    consumerGroup = "order-timeout-group"
)
public class OrderTimeoutConsumer implements RocketMQListener<String> {
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private ProductMapper productMapper;
    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private OrderItemMapper orderItemMapper;

    /**
     * 订单超时取消处理
     * 当订单超时时，将订单状态更新为已取消，并恢复商品库存
     *
     * @param orderId 订单ID
     */
    @Override
    public void onMessage(String orderId) {
        log.info("【订单超时检查】收到订单号: {}", orderId);

        // 1. 查询订单，确认订单当前的真实状态
        Orders order = orderMapper.selectById(orderId);
        if (order == null) {
            log.warn("【订单超时检查】订单不存在，订单号: {}", orderId);
            return;
        }

        int status = order.getStatus();

        // 2. 根据订单状态进行处理
        if (status == 20) {
            // 订单已支付，无需处理
            log.info("【订单超时检查】订单已支付，无需取消，订单号: {}", orderId);
            return;
        } else if (status == 10) {
            // 订单待支付，执行取消操作
            cancelOrder(order);
        } else {
            // 其他状态（已发货、已完成、已取消等），无需处理
            log.info("【订单超时检查】订单状态为{}，无需处理，订单号: {}", status, orderId);
        }
    }

    /**
     * 取消订单并恢复库存
     *
     * @param order 订单对象
     */
    private void cancelOrder(Orders order) {
        String orderId = String.valueOf(order.getId());
        log.info("【订单超时检查】开始取消订单，订单号: {}", orderId);

        // 使用乐观锁更新订单状态
        // 只有当订单状态为待支付(10)且版本号匹配时才更新
        LambdaUpdateWrapper<Orders> orderUpdateWrapper = new LambdaUpdateWrapper<>();
        orderUpdateWrapper.set(Orders::getStatus, 50)
                .eq(Orders::getId, order.getId())
                .eq(Orders::getStatus, 10)
                .eq(Orders::getVersion, order.getVersion());

        int result = orderMapper.update(null, orderUpdateWrapper);

        if (result == 0) {
            // 乐观锁更新失败，可能是订单状态已变更或版本号不匹配
            log.warn("【订单超时检查】取消订单失败，订单可能已被处理，订单号: {}", orderId);
            return;
        }

        log.info("【订单超时检查】订单状态已更新为已取消，订单号: {}", orderId);

        // 恢复商品库存
        restoreStock(orderId);

        log.info("【订单超时检查】订单取消成功，订单号: {}", orderId);
    }

    /**
     * 恢复订单中商品的库存
     *
     * @param orderId 订单ID
     */
    private void restoreStock(String orderId) {
        // 查询订单商品明细
        List<OrderItem> orderItemList = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId)
        );

        if (orderItemList == null || orderItemList.isEmpty()) {
            log.info("【订单超时检查】订单无商品明细，无需恢复库存，订单号: {}", orderId);
            return;
        }

        for (OrderItem orderItem : orderItemList) {
            Long productId = orderItem.getProductId();
            Integer quantity = orderItem.getQuantity();

            // 查询商品当前信息
            Product product = productMapper.selectById(productId);
            if (product == null) {
                log.warn("【订单超时检查】商品不存在，商品ID: {}", productId);
                continue;
            }

            // 使用乐观锁恢复库存
            LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.setSql("stock = stock + " + quantity)
                    .setSql("version = version + 1")
                    .eq(Product::getId, productId)
                    .eq(Product::getVersion, product.getVersion());

            int rows = productMapper.update(null, updateWrapper);

            if (rows == 0) {
                // 乐观锁更新失败，记录日志但不抛出异常
                log.warn("【订单超时检查】恢复库存失败（乐观锁冲突），商品ID: {}，数量: {}",
                        productId, quantity);
            } else {
                log.info("【订单超时检查】恢复库存成功，商品ID: {}，数量: {}", productId, quantity);
            }

            // 删除Redis缓存
            String productKey = "product:" + productId;
            redisTemplate.delete(productKey);
            log.info("【订单超时检查】清除商品缓存，商品ID: {}", productId);
        }
    }

}
