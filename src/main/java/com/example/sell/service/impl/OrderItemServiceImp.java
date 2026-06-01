package com.example.sell.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.sell.dao.OrderItemMapper;
import com.example.sell.entity.OrderItem;
import com.example.sell.service.OrderItemService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author 屈轩
 */
@Service
public class OrderItemServiceImp extends ServiceImpl<OrderItemMapper, OrderItem> implements OrderItemService {

    @Override
    public List<OrderItem> getOrderDetail(Long orderId) {
        if(orderId==null){
            throw new RuntimeException("订单ID不能为空");
        }

        return this.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
    }
}
