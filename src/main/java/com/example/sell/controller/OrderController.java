package com.example.sell.controller;

import com.example.sell.common.R;
import com.example.sell.dto.OrderListRequest;
import com.example.sell.vo.OrderDetailVO;
import com.example.sell.vo.OrderVO;
import com.example.sell.service.OrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author 屈轩
 */
@RestController
@RequestMapping("/order")
public class OrderController {
    @Resource
    private OrderService orderService;

    /**
     * 创建订单
     * @param orderListRequest 订单请求列表
     * @return 订单号
     */
    @PostMapping("/create")
    public R<String> create(@RequestBody OrderListRequest orderListRequest) {
        String orderNo = orderService.createOrder(orderListRequest);
        return R.ok(orderNo);
    }

    /**
     * 用户查询订单列表
     * @return 订单列表
     */
    @GetMapping("/list")
    public R<List<OrderVO>> list() {
        List<OrderVO> orderVOList = orderService.getOrderList();
        return R.ok(orderVOList);
    }

    /**
     * 查询订单详情（根据订单ID）
     * @param orderId 订单ID
     * @return 订单详情
     */
    @GetMapping("/detail/{orderId}")
    public R<OrderDetailVO> detail(@PathVariable Long orderId) {
        OrderDetailVO orderDetail = orderService.getOrderDetail(orderId);
        return R.ok(orderDetail);
    }

    /**
     * 查询订单详情（根据订单号）
     * 用于支付成功后查询订单状态
     * @param orderNo 订单号
     * @return 订单详情
     */
    @GetMapping("/detail")
    public R<OrderDetailVO> detailByOrderNo(@RequestParam String orderNo) {
        OrderDetailVO orderDetail = orderService.getOrderDetailByOrderNo(orderNo);
        return R.ok(orderDetail);
    }

    /**
     * 用户取消订单
     * @param orderId 订单ID
     * @return 操作结果
     */
    @PostMapping("/cancel")
    public R<String> cancel(@RequestParam Long orderId) {
        orderService.cancel(orderId);
        return R.ok("取消成功");
    }

    /**
     * 用户确认收货
     * @param orderId 订单ID
     * @return 操作结果
     */
    @PostMapping("/confirmReceive")
    public R<String> confirmReceive(@RequestParam Long orderId) {
        orderService.confirmReceive(orderId);
        return R.ok("确认收货成功");
    }
}
