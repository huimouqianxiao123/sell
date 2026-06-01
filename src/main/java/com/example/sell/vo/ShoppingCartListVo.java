package com.example.sell.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author 屈轩
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingCartListVo {
    private List<ShoppingCartVo> list;
    private BigDecimal totalPrice;
}
