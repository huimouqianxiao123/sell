package com.example.sell.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author 屈轩
 * <p>订单返回类</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderVO {
    private Long id;
    private String orderNo; //订单号
    private Long userId;
    private BigDecimal totalAmount; //订单总金额
    private Integer status; //订单状态
    private String snapshot;//快照
}
