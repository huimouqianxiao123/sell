package com.example.sell.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sell.entity.ShoppingCart;
import com.example.sell.vo.ShoppingCartVo;

import java.util.List;

/**
 * @author 屈轩
 */
public interface ShoppingCartMapper extends BaseMapper<ShoppingCart> {

    List<ShoppingCartVo> listShoppingCartVo(Long userId);

}
