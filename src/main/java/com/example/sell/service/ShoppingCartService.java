package com.example.sell.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.sell.entity.ShoppingCart;
import com.example.sell.vo.ShoppingCartListVo;

import java.util.List;

/**
 * @author 屈轩
 */
public interface ShoppingCartService extends IService<ShoppingCart> {


    void addCart(String productId, Integer count);

    void updateCart(String productId, Integer count);

    void deleteCartByProductIds(List<Integer> productIds);



    ShoppingCartListVo getShoppingCarts();
}
