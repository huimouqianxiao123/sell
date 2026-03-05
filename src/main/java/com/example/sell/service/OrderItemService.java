package com.example.sell.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.example.sell.domain.pojo.OrderItem;

import java.util.List;

/**
 * @author 屈轩
 */
public interface OrderItemService extends IService<OrderItem> {

    List<OrderItem> getOrderDetail(Long orderId);
}
