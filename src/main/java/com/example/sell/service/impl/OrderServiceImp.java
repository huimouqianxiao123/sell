package com.example.sell.service.impl;

import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.sell.common.UserContext;
import com.example.sell.common.UserInfo;
import com.example.sell.dao.OrderItemMapper;
import com.example.sell.dao.OrderMapper;
import com.example.sell.dao.ProductMapper;
import com.example.sell.dao.ShoppingCartMapper;
import com.example.sell.dto.OrderListRequest;
import com.example.sell.dto.OrderRequest;
import com.example.sell.enums.OrderStatusEnum;
import com.example.sell.entity.OrderItem;
import com.example.sell.entity.Orders;
import com.example.sell.entity.Product;
import com.example.sell.entity.ShoppingCart;
import com.example.sell.vo.OrderDetailVO;
import com.example.sell.vo.OrderItemVO;
import com.example.sell.vo.OrderVO;
import com.example.sell.service.OrderService;
import jakarta.annotation.Resource;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author 屈轩
 */
@Service
public class OrderServiceImp extends ServiceImpl<OrderMapper, Orders> implements OrderService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private ProductMapper productMapper;
    @Resource
    private OrderItemMapper orderItemMapper;
    @Resource
    private ShoppingCartMapper shoppingCartMapper;

    private static final String PRODUCT_KEY = "product:";

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Override
    @Transactional
    public String createOrder(OrderListRequest orderListRequest) {
        if (orderListRequest == null || orderListRequest.getOrderRequests().isEmpty()) {
            throw new RuntimeException("参数错误");
        }
        // 1. 这里的类型改为 List<Long>
        List<Long> productIds = orderListRequest.getOrderRequests().stream()
                // 2. 使用方法引用，提取 OrderRequest 里的 productId
                .map(OrderRequest::getProductId)
                .toList();
        Map<Long, Integer> buyCountMap = orderListRequest.getOrderRequests().stream()
                .collect(Collectors.toMap(OrderRequest::getProductId, OrderRequest::getCount));
        List<Product> products = productMapper.selectByIds(productIds);
        if (products.size() != productIds.size()) {
            throw new RuntimeException("部分商品不存在或已下架");
        }
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        String orderNo = UUID.randomUUID().toString().replace("-", "");
        for (Product product : products) {
            Integer buyCount = buyCountMap.get(product.getId());
            if (product.getStock() < buyCount) {
                throw new RuntimeException("商品[" + product.getName() + "]库存不足");
            }
            product.setStock(product.getStock() - buyCount);
            int rows = productMapper.updateById(product);
            if (rows == 0) {
                throw new RuntimeException("系统繁忙（抢单人数过多），请重试");
            }
            BigDecimal itemTotal = product.getPrice().multiply(new BigDecimal(buyCount));
            OrderItem orderItem = OrderItem.builder()
                    .orderNo(orderNo)
                    .productId(product.getId())
                    .productName(product.getName())
                    .unitPrice(product.getPrice())
                    .quantity(buyCount)
                    .totalPrice(itemTotal)
                    .productDescription(product.getDescription())
                    .productImage(product.getImage())
                    .updateTime(LocalDateTime.now())
                    .createTime(LocalDateTime.now())
                    .build();
            orderItems.add(orderItem);
            // 5.3 累加金额
            totalAmount = totalAmount.add(itemTotal);
        }
       
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();
        Orders order = new Orders();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setStatus(10);
        order.setOrderType(1);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        this.save(order);
        Long orderId = order.getId();
        for (OrderItem item : orderItems) {
            item.setOrderId(orderId);
            orderItemMapper.insert(item);
        }

        // 使用事务同步器，在事务提交成功后删除Redis缓存
        // 这样可以保证：如果事务回滚，Redis不会被错误地删除
        // 下次查询时会从数据库重新加载最新数据
        List<Long> productIdsToUpdate = new ArrayList<>(productIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long id : productIdsToUpdate) {
                    redisTemplate.delete(PRODUCT_KEY + id);
                }

                UserInfo userInfo = UserContext.getUserInfo();
                if (userInfo != null && userInfo.getId() != null) {
                    Long userId = userInfo.getId();
                    shoppingCartMapper.delete(new LambdaUpdateWrapper<ShoppingCart>()
                            .eq(ShoppingCart::getUserId, userId)
                            .in(ShoppingCart::getProductId, productIdsToUpdate)
                    );
                }
                rocketMQTemplate.syncSend(
                        "order-timeout-topic",
                        MessageBuilder.withPayload(orderId).build(),
                        3000,
                        16
                );
            }
        });

        return orderNo;
    }

    @Override
    public List<OrderVO> getOrderList() {
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();
        List<Orders> orders = this.list(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getUserId, userId)
        );
       List<OrderVO>orderVOList=null;
       orderVOList=orders.stream()
                .map(order -> OrderVO.builder()
                        .id(order.getId())
                        .orderNo(order.getOrderNo())
                        .userId(order.getUserId())
                        .totalAmount(order.getTotalAmount())
                        .status(order.getStatus())
                        .snapshot(order.getSnapshot())
                        .build())
                .toList();
        return orderVOList;
    }

    @Override
    public OrderDetailVO getOrderDetail(Long orderId) {
        if (orderId == null) {
            throw new RuntimeException("订单ID不能为空");
        }
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();
        Orders order = this.getOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getId, orderId)
                .eq(Orders::getUserId, userId)
        );
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        // 查询订单项
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId)
        );

        List<OrderItemVO> orderItemVOList = orderItems.stream()
                .map(item -> OrderItemVO.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .productImage(item.getProductImage())
                        .productDescription(item.getProductDescription())
                        .unitPrice(item.getUnitPrice())
                        .quantity(item.getQuantity())
                        .totalPrice(item.getTotalPrice())
                        .build())
                .collect(Collectors.toList());

        OrderStatusEnum statusEnum = OrderStatusEnum.getByCode(order.getStatus());

        return OrderDetailVO.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .statusDesc(statusEnum != null ? statusEnum.getDesc() : "未知状态")
                .transactionId(order.getTransactionId())
                .payTime(order.getPayTime())
                .expireTime(order.getExpireTime())
                .createTime(order.getCreateTime())
                .updateTime(order.getUpdateTime())
                .orderItems(orderItemVOList)
                .build();
    }

    @Override
    public OrderDetailVO getOrderDetailByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isEmpty()) {
            throw new RuntimeException("订单号不能为空");
        }
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();
        Orders order = this.getOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getOrderNo, orderNo)
                .eq(Orders::getUserId, userId)
        );
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        // 查询订单项
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId())
        );

        List<OrderItemVO> orderItemVOList = orderItems.stream()
                .map(item -> OrderItemVO.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .productImage(item.getProductImage())
                        .productDescription(item.getProductDescription())
                        .unitPrice(item.getUnitPrice())
                        .quantity(item.getQuantity())
                        .totalPrice(item.getTotalPrice())
                        .build())
                .collect(Collectors.toList());

        OrderStatusEnum statusEnum = OrderStatusEnum.getByCode(order.getStatus());

        return OrderDetailVO.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .statusDesc(statusEnum != null ? statusEnum.getDesc() : "未知状态")
                .transactionId(order.getTransactionId())
                .payTime(order.getPayTime())
                .expireTime(order.getExpireTime())
                .createTime(order.getCreateTime())
                .updateTime(order.getUpdateTime())
                .orderItems(orderItemVOList)
                .build();
    }

    @Override
    @Transactional
    public void cancel(Long orderId) {
        if(orderId==null){
            throw new RuntimeException("订单ID不能为空");
        }
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();
        Orders order = this.getOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getId, orderId)
                .eq(Orders::getUserId, userId)
        );
        if(order==null){
            throw new RuntimeException("订单不存在");
        }
        if(order.getStatus()!=10){
            throw new RuntimeException("订单状态错误");
        }
        order.setStatus(50);
        this.updateById(order);
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId)
        );

        // 收集需要恢复库存的商品ID
        List<Long> productIdsToRestore = new ArrayList<>();

        for (OrderItem orderItem : orderItems) {
            Long productId = orderItem.getProductId();
            Integer quantity = orderItem.getQuantity();
            Product product = productMapper.selectById(productId);
            if (product != null) {
                product.setStock(product.getStock() + quantity);
                int rows = productMapper.updateById(product);
                if (rows == 0) {
                    throw new RuntimeException("恢复库存失败，请重试");
                }
            }
            productIdsToRestore.add(productId);
        }

        // 使用事务同步器，在事务提交成功后删除Redis缓存
        // 这样可以保证：如果事务回滚，Redis不会被错误地删除
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long productId : productIdsToRestore) {
                    String productKey = PRODUCT_KEY + productId;
                    redisTemplate.delete(productKey);
                }
            }
        });
    }

    @Override
    @Transactional
    public void confirmReceive(Long orderId) {
        if (orderId == null) {
            throw new RuntimeException("订单ID不能为空");
        }
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();
        Orders order = this.getOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getId, orderId)
                .eq(Orders::getUserId, userId)
        );
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        // 只有已发货的订单才能确认收货
        if (order.getStatus() != OrderStatusEnum.SHIPPED.getCode()) {
            throw new RuntimeException("订单状态错误，只有已发货的订单才能确认收货");
        }
        order.setStatus(OrderStatusEnum.COMPLETED.getCode());
        order.setUpdateTime(LocalDateTime.now());
        this.updateById(order);
    }
}
