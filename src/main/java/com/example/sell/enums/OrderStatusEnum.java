package com.example.sell.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderStatusEnum {

    PENDING_PAYMENT(10, "待支付"),
    PAID(20, "已支付"),
    SHIPPED(30, "已发货"),
    COMPLETED(40, "已完成"),
    CANCELLED(50, "已取消/超时关闭");

    private final Integer code;
    private final String desc;

    public static OrderStatusEnum getByCode(Integer code) {
        for (OrderStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
