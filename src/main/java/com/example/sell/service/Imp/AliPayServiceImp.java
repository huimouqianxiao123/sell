package com.example.sell.service.Imp;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.sell.common.UserContext;
import com.example.sell.common.UserInfo;
import com.example.sell.config.AlipayConfig;
import com.example.sell.dao.OrderItemMapper;
import com.example.sell.dao.OrderMapper;
import com.example.sell.dao.ProductMapper;
import com.example.sell.domain.Dto.RefundRequest;
import com.example.sell.domain.enums.OrderStatusEnum;
import com.example.sell.domain.pojo.OrderItem;
import com.example.sell.domain.pojo.Orders;
import com.example.sell.domain.pojo.Product;
import com.example.sell.service.AlipayService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author 屈轩
 */
@Service
@Slf4j
public class AliPayServiceImp implements AlipayService {

    @Autowired
    private AlipayConfig alipayConfig;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private ProductMapper productMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private static final String PRODUCT_KEY = "product:";

    /**
     * PC端网站支付（返回支付宝支付页面HTML）
     * 如果是移动端，请使用 AlipayTradeWapPayRequest
     */
    @Override
    public String pay(String orderNo, String amount) {
        // 1. 参数校验
        if (orderNo == null || amount == null) {
            throw new RuntimeException("参数错误");
        }

        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();

        // 2. 查询订单
        Orders order = orderMapper.selectOne(new QueryWrapper<Orders>()
                .eq("user_id", userId)
                .eq("order_no", orderNo));

        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        // 3. 金额校验（防止前端篡改金额）
        if (order.getTotalAmount().compareTo(new BigDecimal(amount)) != 0) {
            throw new RuntimeException("支付金额与订单金额不符");
        }

        // 4. 查询订单商品明细
        List<OrderItem> orderItems = orderItemMapper.selectList(new QueryWrapper<OrderItem>()
                .eq("order_id", order.getId()));

        if (CollectionUtils.isEmpty(orderItems)) {
            throw new RuntimeException("订单商品信息为空");
        }

        // 5. 初始化支付宝客户端
        AlipayClient alipayClient = new DefaultAlipayClient(
                alipayConfig.getGatewayUrl(),
                alipayConfig.getAppId(),
                alipayConfig.getMerchantPrivateKey(),
                "json",
                alipayConfig.getCharset(),
                alipayConfig.getAlipayPublicKey(),
                alipayConfig.getSignType());

        // 6. 构建支付请求（PC端使用 AlipayTradePagePayRequest）
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();

        // 设置同步回调地址（支付成功后跳转的页面）
        request.setReturnUrl(alipayConfig.getReturnUrl());
        // 设置异步通知地址（支付宝主动通知支付结果）
        request.setNotifyUrl(alipayConfig.getNotifyUrl());

        // 7. 组装业务参数
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(orderNo); // 商户订单号
        model.setTotalAmount(amount); // 订单金额
        model.setProductCode("FAST_INSTANT_TRADE_PAY"); // 销售产品码（PC端固定值）

        // 设置商品标题（主商品名或拼接多个商品）
        String subject = buildSubject(orderItems);
        model.setSubject(subject);

        // 设置商品描述（展示所有商品详情）
        String body = buildBody(orderItems);
        model.setBody(body);

        // 设置超时时间（可选）
        model.setTimeoutExpress("30m"); // 30分钟未支付自动关闭

        request.setBizModel(model);

        try {
            // 8. 调用支付宝接口（返回的是HTML表单字符串）
            AlipayTradePagePayResponse response = alipayClient.pageExecute(request);

            if (!response.isSuccess()) {
                throw new RuntimeException("支付宝下单失败：" + response.getMsg());
            }

            // 返回HTML表单，前端直接渲染即可自动跳转支付宝收银台
            return response.getBody();

        } catch (AlipayApiException e) {
            throw new RuntimeException("支付宝接口调用异常", e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String notify(HttpServletRequest request) throws AlipayApiException {
        log.info("收到支付宝异步回调！");

        Map<String, String> params = new HashMap<>();
        Map<String, String[]> requestParams = request.getParameterMap();
        for (String name : requestParams.keySet()) {
            String[] values = requestParams.get(name);
            String valueStr = String.join(",", values);
            params.put(name, valueStr);
        }

        log.info("支付宝回调参数：{}", params);

        // 检查是否是前端模拟请求（trade_no以MOCK_开头）
        String tradeNoParam = params.get("trade_no");
        boolean isMockRequest = tradeNoParam != null && tradeNoParam.startsWith("MOCK_");

        boolean signVerified;
        if (isMockRequest) {
            log.warn("检测到模拟请求，跳过签名验证！trade_no: {}", tradeNoParam);
            signVerified = true;
        } else {
            signVerified = AlipaySignature.rsaCheckV1(
                    params,
                    alipayConfig.getAlipayPublicKey(),
                    alipayConfig.getCharset(),
                    alipayConfig.getSignType());
        }

        if (!signVerified) {
            log.error("验签失败！可能是公钥配置错误");
            return "failure";
        }

        log.info("验签成功");

        String tradeStatus = params.get("trade_status");
        String outTradeNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String totalAmount = params.get("total_amount");
        String gmtPayment = params.get("gmt_payment");

        log.info("订单号：{}，支付宝交易号：{}，交易状态：{}，支付金额：{}，支付时间：{}",
                outTradeNo, tradeNo, tradeStatus, totalAmount, gmtPayment);

        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            log.info("交易状态不是成功，无需处理，当前状态：{}", tradeStatus);
            return "success";
        }

        Orders order = orderMapper.selectOne(new QueryWrapper<Orders>()
                .eq("order_no", outTradeNo));

        if (order == null) {
            log.error("订单不存在，订单号：{}", outTradeNo);
            return "failure";
        }

        if (order.getStatus().equals(OrderStatusEnum.PAID.getCode())) {
            log.info("订单已支付，无需重复处理，订单号：{}，当前状态：{}",
                    outTradeNo, OrderStatusEnum.getByCode(order.getStatus()).getDesc());
            return "success";
        }

        if (!order.getStatus().equals(OrderStatusEnum.PENDING_PAYMENT.getCode())) {
            log.error("订单状态异常，无法支付，订单号：{}，当前状态：{}",
                    outTradeNo, OrderStatusEnum.getByCode(order.getStatus()).getDesc());
            return "failure";
        }

        if (totalAmount == null || new BigDecimal(totalAmount).compareTo(order.getTotalAmount()) != 0) {
            log.error("支付金额与订单金额不符，订单号：{}，订单金额：{}，支付金额：{}",
                    outTradeNo, order.getTotalAmount(), totalAmount);
            return "failure";
        }

        log.info("支付金额验证通过，订单号：{}，金额：{}", outTradeNo, totalAmount);

        // 使用乐观锁更新订单状态
        // 只有当订单状态为待支付且版本号匹配时才更新
        LambdaUpdateWrapper<Orders> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.set(Orders::getStatus, OrderStatusEnum.PAID.getCode())
                .set(Orders::getTransactionId, tradeNo)
                .set(Orders::getPayTime, LocalDateTime.now())
                .set(Orders::getUpdateTime, LocalDateTime.now())
                .eq(Orders::getId, order.getId())
                .eq(Orders::getStatus, OrderStatusEnum.PENDING_PAYMENT.getCode())
                .eq(Orders::getVersion, order.getVersion());

        int result = orderMapper.update(null, updateWrapper);
        if (result != 1) {
            log.error("更新订单状态失败，可能是订单已被处理，订单号：{}", outTradeNo);
            // 返回 success 避免支付宝重复通知
            return "success";
        }

        log.info("订单状态更新成功，订单号：{}，支付宝交易号：{}，支付时间：{}",
                outTradeNo, tradeNo, order.getPayTime());

        List<OrderItem> orderItems = orderItemMapper.selectList(new QueryWrapper<OrderItem>()
                .eq("order_id", order.getId()));

        // 收集需要清理缓存的商品ID
        List<Long> productIdsToClear = new ArrayList<>();
        for (OrderItem orderItem : orderItems) {
            productIdsToClear.add(orderItem.getProductId());
        }

        // 使用事务同步器，在事务提交成功后删除Redis缓存
        // 这样可以保证：如果事务回滚，Redis不会被错误地删除
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long productId : productIdsToClear) {
                    String productKey = PRODUCT_KEY + productId;
                    redisTemplate.delete(productKey);
                    log.info("清理商品缓存，商品ID：{}，缓存Key：{}", productId, productKey);
                }
            }
        });

        log.info("订单支付处理完成，订单号：{}", outTradeNo);
        return "success";
    }

    /**
     * 构建支付标题（取第一个商品名或拼接）
     */
    private String buildSubject(List<OrderItem> orderItems) {
        if (orderItems.size() == 1) {
            return orderItems.get(0).getProductName();
        } else {
            // 多个商品时显示：商品A等3件商品
            return orderItems.get(0).getProductName() + "等" + orderItems.size() + "件商品";
        }
    }

    /**
     * 构建商品描述（展示详情列表）
     */
    private String buildBody(List<OrderItem> orderItems) {
        return orderItems.stream()
                .map(item -> String.format("%s x%d (¥%s)",
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice()))
                .collect(Collectors.joining(", "));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String refund(RefundRequest refundRequest) throws AlipayApiException {
        if (refundRequest == null || refundRequest.getOrderId() == null) {
            throw new RuntimeException("参数错误");
        }

        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();

        // 1. 查询订单
        Orders order = orderMapper.selectOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getId, refundRequest.getOrderId())
                .eq(Orders::getUserId, userId));

        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        // 2. 校验订单状态（只有已支付的订单才能退款）
        if (!order.getStatus().equals(OrderStatusEnum.PAID.getCode())) {
            throw new RuntimeException("订单状态错误，只有已支付的订单才能退款");
        }

        // 3. 确定退款金额
        BigDecimal refundAmount = refundRequest.getRefundAmount();
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            refundAmount = order.getTotalAmount(); // 全额退款
        }

        if (refundAmount.compareTo(order.getTotalAmount()) > 0) {
            throw new RuntimeException("退款金额不能大于订单金额");
        }

        // 4. 调用支付宝退款接口
        AlipayClient alipayClient = new DefaultAlipayClient(
                alipayConfig.getGatewayUrl(),
                alipayConfig.getAppId(),
                alipayConfig.getMerchantPrivateKey(),
                "json",
                alipayConfig.getCharset(),
                alipayConfig.getAlipayPublicKey(),
                alipayConfig.getSignType());

        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        AlipayTradeRefundModel model = new AlipayTradeRefundModel();
        model.setOutTradeNo(order.getOrderNo());
        model.setTradeNo(order.getTransactionId());
        model.setRefundAmount(refundAmount.toString());
        model.setRefundReason(refundRequest.getRefundReason() != null ? refundRequest.getRefundReason() : "用户申请退款");

        request.setBizModel(model);

        AlipayTradeRefundResponse response = alipayClient.execute(request);

        if (!response.isSuccess()) {
            log.error("支付宝退款失败，订单号：{}，错误信息：{}", order.getOrderNo(), response.getMsg());
            throw new RuntimeException("退款失败：" + response.getMsg());
        }

        // 5. 更新订单状态为已取消/退款
        order.setStatus(OrderStatusEnum.CANCELLED.getCode());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);

        // 6. 恢复库存
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));

        // 收集需要恢复库存的商品ID
        List<Long> productIdsToRestore = new ArrayList<>();

        for (OrderItem orderItem : orderItems) {
            Long productId = orderItem.getProductId();
            Integer quantity = orderItem.getQuantity();
            Product product = productMapper.selectById(productId);
            if (product != null) {
                LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.setSql("stock = stock + " + quantity);
                updateWrapper.setSql("version = version + 1");
                updateWrapper.eq(Product::getId, productId);
                updateWrapper.eq(Product::getVersion, product.getVersion());
                int rows = productMapper.update(null, updateWrapper);
                if (rows == 0) {
                    log.warn("恢复库存失败，商品ID：{}", productId);
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

        log.info("订单退款成功，订单号：{}，退款金额：{}", order.getOrderNo(), refundAmount);
        return "退款成功";
    }

    /**
     * 模拟支付成功（仅用于测试环境）
     * 直接更新订单状态，不经过支付宝验签
     * 
     * @param orderNo 订单号
     * @return 处理结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String mockPaymentSuccess(String orderNo) {
        log.info("【模拟支付】开始处理订单：{}", orderNo);

        // 1. 查询订单
        Orders order = orderMapper.selectOne(new QueryWrapper<Orders>()
                .eq("order_no", orderNo));

        if (order == null) {
            log.error("【模拟支付】订单不存在，订单号：{}", orderNo);
            throw new RuntimeException("订单不存在");
        }

        // 2. 检查订单状态
        if (order.getStatus().equals(OrderStatusEnum.PAID.getCode())) {
            log.info("【模拟支付】订单已支付，无需重复处理，订单号：{}", orderNo);
            return "订单已支付";
        }

        if (!order.getStatus().equals(OrderStatusEnum.PENDING_PAYMENT.getCode())) {
            log.error("【模拟支付】订单状态异常，无法支付，订单号：{}，当前状态：{}",
                    orderNo, OrderStatusEnum.getByCode(order.getStatus()).getDesc());
            throw new RuntimeException("订单状态异常，无法支付");
        }

        // 3. 使用乐观锁更新订单状态
        String mockTradeNo = "MOCK_" + System.currentTimeMillis();
        LambdaUpdateWrapper<Orders> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.set(Orders::getStatus, OrderStatusEnum.PAID.getCode())
                .set(Orders::getTransactionId, mockTradeNo)
                .set(Orders::getPayTime, LocalDateTime.now())
                .set(Orders::getUpdateTime, LocalDateTime.now())
                .eq(Orders::getId, order.getId())
                .eq(Orders::getStatus, OrderStatusEnum.PENDING_PAYMENT.getCode())
                .eq(Orders::getVersion, order.getVersion());

        int result = orderMapper.update(null, updateWrapper);
        if (result != 1) {
            log.error("【模拟支付】更新订单状态失败，可能是订单已被处理，订单号：{}", orderNo);
            throw new RuntimeException("更新订单状态失败");
        }

        log.info("【模拟支付】订单状态更新成功，订单号：{}，模拟交易号：{}", orderNo, mockTradeNo);

        // 4. 清理商品缓存
        List<OrderItem> orderItems = orderItemMapper.selectList(new QueryWrapper<OrderItem>()
                .eq("order_id", order.getId()));

        // 收集需要清理缓存的商品ID
        List<Long> productIdsToClear = new ArrayList<>();
        for (OrderItem orderItem : orderItems) {
            productIdsToClear.add(orderItem.getProductId());
        }

        // 使用事务同步器，在事务提交成功后删除Redis缓存
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long productId : productIdsToClear) {
                    String productKey = PRODUCT_KEY + productId;
                    redisTemplate.delete(productKey);
                    log.info("【模拟支付】清理商品缓存，商品ID：{}，缓存Key：{}", productId, productKey);
                }
            }
        });

        log.info("【模拟支付】处理完成，订单号：{}", orderNo);
        return "success";
    }
}
