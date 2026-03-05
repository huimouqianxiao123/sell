package com.example.sell.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * @author 屈轩
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingCartVo {
    Long id;
    //购物车的主键id
    Long productId;
    String productName;
    String productImage;
    String productDescription;
    Integer quantity;
    BigDecimal price;
    Boolean selected;
}
