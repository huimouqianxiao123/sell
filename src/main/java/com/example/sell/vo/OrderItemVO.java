package com.example.sell.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * @author 屈轩
 * <p>订单项返回类</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemVO {
    private Long id;
    private Long productId;
    private String productName;
    private String productImage;
    private String productDescription;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal totalPrice;
}
