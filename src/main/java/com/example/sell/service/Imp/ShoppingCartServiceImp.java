package com.example.sell.service.Imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.sell.common.UserContext;
import com.example.sell.dao.ShoppingCartMapper;
import com.example.sell.domain.pojo.ShoppingCart;
import com.example.sell.domain.vo.ShoppingCartListVo;
import com.example.sell.domain.vo.ShoppingCartVo;
import com.example.sell.common.UserInfo;
import com.example.sell.service.ShoppingCartService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @author 屈轩
 */
@Service
public class ShoppingCartServiceImp extends ServiceImpl<ShoppingCartMapper, ShoppingCart> implements ShoppingCartService {


    @Resource
    private ShoppingCartMapper shoppingCartMapper;

    /**
     * 加入购物车
     *
     * @param productId
     * @param count
     */
    @Override
    public void addCart(String productId, Integer count) {
        if (productId == null) {
            throw new RuntimeException("商品ID不能为空");
        }
        if (count == null || count <= 0) {
            throw new RuntimeException("商品数量不能小于1");
        }
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();
        Long pId = Long.parseLong(productId);
        ShoppingCart existingCartItem = this.getOne(new LambdaQueryWrapper<ShoppingCart>()
                .eq(ShoppingCart::getUserId, userId)
                .eq(ShoppingCart::getProductId, pId));
        if (existingCartItem != null) {
            existingCartItem.setQuantity(existingCartItem.getQuantity() + count);
            existingCartItem.setUpdateTime(LocalDateTime.now());
            Boolean update = this.update(existingCartItem, new LambdaQueryWrapper<ShoppingCart>()
                    .eq(ShoppingCart::getUserId, userId)
                    .eq(ShoppingCart::getProductId, pId));
            if (!update) {
                throw new RuntimeException("加入购物车失败");
            }
        } else {
            ShoppingCart shoppingCart = ShoppingCart.builder()
                    .userId(userId)
                    .productId(pId)
                    .quantity(count)
                    .selected(true)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            Boolean save = this.save(shoppingCart);
            if (!save) {
                throw new RuntimeException("加入购物车失败");
            }
        }
    }

    /**
     * 修改购物车商品数量，count可以为负值
     *
     * @param productId
     * @param count
     */
    @Override
    public void updateCart(String productId, Integer count) {
        if (productId == null) {
            throw new RuntimeException("商品ID不能为空");
        }
        if (count == null) {
            throw new RuntimeException("商品数量不能小于1");
        }
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
        }
        Long userId = userInfo.getId();
        ShoppingCart existingCartItem = this.getOne(new LambdaQueryWrapper<ShoppingCart>()
                .eq(ShoppingCart::getUserId, userId)
                .eq(ShoppingCart::getProductId, productId));
        if (existingCartItem == null) {
            throw new RuntimeException("购物车中未找到该商品，无法修改数量");
        }
        int newQuantity = existingCartItem.getQuantity() + count;
        if (newQuantity <= 0) {
            throw new RuntimeException("商品数量不能小于1");
        }
        existingCartItem.setQuantity(newQuantity);
        existingCartItem.setUpdateTime(LocalDateTime.now());
        boolean update = this.updateById(existingCartItem);
        if (!update) {
            throw new RuntimeException("修改购物车商品数量失败");
        }
    }

    @Override
    public void deleteCartByProductIds(List<Integer> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            throw new RuntimeException("请选择要删除的商品");
        }
        boolean delete = this.removeByIds(productIds);
        if (!delete) {
            throw new RuntimeException("删除购物车商品失败");
        }
    }

    @Override
    public ShoppingCartListVo getShoppingCarts() {
        UserInfo userInfo = UserContext.getUserInfo();
        if (userInfo == null || userInfo.getId() == null) {
            throw new RuntimeException("用户未登录");
            // 或者抛出特定的鉴权异常，让全局异常处理器去处理跳转登录
        }
        Long userId = userInfo.getId();
        List<ShoppingCartVo> list = shoppingCartMapper.listShoppingCartVo(userId);
        BigDecimal totalPrice = list.stream()
                .filter(ShoppingCartVo::getSelected)
                .map(ShoppingCartVo::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ShoppingCartListVo shoppingCartListVo = ShoppingCartListVo.builder()
                .totalPrice(totalPrice)
                .list( list)
                .build();
        return shoppingCartListVo;
    }

}
