package com.example.sell.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sell.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
