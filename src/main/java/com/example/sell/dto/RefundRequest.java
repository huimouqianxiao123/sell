package com.example.sell.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * @author 屈轩
 * <p>退款请求DTO</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {
    /**
     * 订单ID
     */
    private Long orderId;

    /**
     * 退款金额（如果为null或0则全额退款）
     */
    private BigDecimal refundAmount;

    /**
     * 退款原因
     */
    private String refundReason;
}
