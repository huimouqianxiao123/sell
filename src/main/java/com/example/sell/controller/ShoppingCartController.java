package com.example.sell.controller;

import com.example.sell.common.R;
import com.example.sell.vo.ShoppingCartListVo;
import com.example.sell.vo.ShoppingCartVo;
import com.example.sell.service.ShoppingCartService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 屈轩
 */
@RestController
@RequestMapping("/shoppingCart")
public class ShoppingCartController {
    @Resource
    private ShoppingCartService shoppingCartService;

    @PostMapping("/add")
    public R<String> addCart(String productId, Integer count) {
        shoppingCartService.addCart(productId, count);
        return R.ok("添加购物车成功");
    }

    /**
     * 更新购物车
     *
     * @param productId
     * @param count
     * @return
     */
    @PostMapping("/update")
    public R<String> updateCart(String productId, Integer count) {
        shoppingCartService.updateCart(productId, count);
        return R.ok("更新购物车成功");
    }

    /**
     * 删除购物车商品
     *
     * @param productIds
     * @return
     */
    @PostMapping("/delete")
    public R<String> deleteCart(List<Integer> productIds) {
        shoppingCartService.deleteCartByProductIds(productIds);
        return R.ok("删除购物车成功");
    }

    @PostMapping("/list")
    public R<ShoppingCartListVo> getShoppingCarts() {
    ShoppingCartListVo shoppingCartListVo  = shoppingCartService.getShoppingCarts();
        return R.ok(shoppingCartListVo);
    }
}
