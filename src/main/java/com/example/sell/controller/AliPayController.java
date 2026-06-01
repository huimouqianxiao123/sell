package com.example.sell.controller;

import com.alipay.api.AlipayApiException;
import com.example.sell.common.R;
import com.example.sell.config.AlipayConfig;
import com.example.sell.dto.RefundRequest;
import com.example.sell.service.AlipayService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * @author 屈轩
 */
@RestController
@RequestMapping("/alipay")
public class AliPayController {

    @Autowired
    private AlipayConfig alipayConfig;
    @Resource
    private AlipayService alipayService;

    /**
     * 支付宝支付
     * 
     * @param orderNo 订单号
     * @param amount  金额
     * @return 支付表单HTML
     */
    @PostMapping("/pay")
    public R<String> pay(@RequestParam String orderNo, @RequestParam String amount) {
        String formHtml = alipayService.pay(orderNo, amount);
        return R.ok(formHtml);
    }

    /**
     * 支付宝异步通知回调
     * 
     * @param request  HTTP请求
     * @param response HTTP响应
     * @throws AlipayApiException 支付宝API异常
     * @throws IOException        IO异常
     */
    @PostMapping("/notify")
    public void notify(HttpServletRequest request, HttpServletResponse response)
            throws AlipayApiException, IOException {
        String result = alipayService.notify(request);
        response.setContentType("text/plain;charset=utf-8");
        PrintWriter writer = response.getWriter();
        writer.write(result);
        writer.flush();
    }

    /**
     * 订单退款
     * 
     * @param refundRequest 退款请求
     * @return 退款结果
     * @throws AlipayApiException 支付宝API异常
     */
    @PostMapping("/refund")
    public R<String> refund(@RequestBody RefundRequest refundRequest) throws AlipayApiException {
        String result = alipayService.refund(refundRequest);
        return R.ok(result);
    }

    /**
     * 支付宝同步回调（支付成功后跳转）
     * 支付宝支付完成后会携带参数跳转到此接口
     * 
     * @param request  HTTP请求
     * @param response HTTP响应
     * @throws AlipayApiException 支付宝API异常
     * @throws IOException        IO异常
     */
    @GetMapping("/return")
    public void returnCallback(HttpServletRequest request, HttpServletResponse response)
            throws AlipayApiException, IOException {
        // 获取支付宝返回的参数
        String outTradeNo = request.getParameter("out_trade_no");
        String tradeNo = request.getParameter("trade_no");
        String totalAmount = request.getParameter("total_amount");
        String sellerId = request.getParameter("seller_id");
        String timestamp = request.getParameter("timestamp");

        // 前端页面地址（本地开发环境）
        String frontendUrl = "http://localhost:5173";
        // 支付成功后的跳转地址（使用 HTML5 History 模式，不使用 hash）
        String redirectUrl = frontendUrl + "/user/order?status=success&orderNo=" + outTradeNo;

        // 重定向到前端订单页面
        response.sendRedirect(redirectUrl);
    }

    /**
     * 模拟支付成功（仅用于测试环境）
     * 前端轮询调用此接口来更新订单状态
     * 
     * @param orderNo 订单号
     * @return 处理结果
     */
    @PostMapping("/mock-pay")
    public R<String> mockPay(@RequestParam String orderNo) {
        String result = alipayService.mockPaymentSuccess(orderNo);
        return R.ok(result);
    }
}
