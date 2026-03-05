package com.example.sell.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @author 屈轩
 * <p>订单详情返回类</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDetailVO {
    private Long id;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private Integer status;
    private String statusDesc;
    private String transactionId;
    private LocalDateTime payTime;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<OrderItemVO> orderItems;
}
