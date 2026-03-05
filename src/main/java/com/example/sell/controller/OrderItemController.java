package com.example.sell.controller;

import com.example.sell.common.R;
import com.example.sell.domain.pojo.OrderItem;
import com.example.sell.service.OrderItemService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * @author 屈轩
 */
@RestController
@RequestMapping("/orderItem")
public class OrderItemController {
    @Resource
    private OrderItemService orderItemService;

    @GetMapping("/list")
    public R<List<OrderItem>> list(@RequestParam Long orderId) {
        List<OrderItem>orderItems=orderItemService.getOrderDetail(orderId);
        return R.ok(orderItems);
    }
}
