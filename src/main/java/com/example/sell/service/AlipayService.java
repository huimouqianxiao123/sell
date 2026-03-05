package com.example.sell.service;

import com.alipay.api.AlipayApiException;
import com.example.sell.domain.Dto.RefundRequest;
import jakarta.servlet.http.HttpServletRequest;

/**
 * @author 屈轩
 */
public interface AlipayService {
    String pay(String orderNo, String amount);

    String notify(HttpServletRequest request) throws AlipayApiException;

    String refund(RefundRequest refundRequest) throws AlipayApiException;

    /**
     * 模拟支付成功（仅用于测试环境）
     * 
     * @param orderNo 订单号
     * @return 处理结果
     */
    String mockPaymentSuccess(String orderNo);
}
