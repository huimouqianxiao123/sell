package com.example.sell.service.impl;

import com.example.sell.entity.SeckillMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 告警通知服务
 * <p>
 * 提供秒杀系统异常告警能力，当出现死信消息、库存异常等情况时触发告警。
 * 当前实现基于日志输出，预留扩展接口支持接入邮件、钉钉、企业微信等通知渠道。
 * </p>
 * <p>
 * 扩展方式：
 * 1. 接入 Spring Boot Mail 发送邮件告警
 * 2. 调用钉钉/企业微信 Webhook 发送群消息
 * 3. 接入 Prometheus + AlertManager 告警体系
 * </p>
 *
 * @author 屈轩
 */
@Slf4j
@Service
public class AlertService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${alert.enabled:true}")
    private boolean alertEnabled;

    @Value("${alert.dingtalk.webhook:}")
    private String dingtalkWebhook;

    /**
     * 发送死信消息告警
     * <p>
     * 当消息超过最大重试次数后触发，意味着有用户的秒杀库存被扣但订单未生成，
     * 且自动恢复已达上限，需要人工介入处理。
     * </p>
     *
     * @param deadLetterMessages 死信消息列表
     */
    public void alertDeadLetterMessages(List<SeckillMessage> deadLetterMessages) {
        if (!alertEnabled || deadLetterMessages == null || deadLetterMessages.isEmpty()) {
            return;
        }

        String alertTitle = "【秒杀系统告警】发现死信消息";
        StringBuilder content = new StringBuilder();
        content.append(alertTitle).append("\n");
        content.append("告警时间: ").append(LocalDateTime.now().format(FORMATTER)).append("\n");
        content.append("死信数量: ").append(deadLetterMessages.size()).append("\n");
        content.append("----------------------------------------\n");

        for (SeckillMessage message : deadLetterMessages) {
            content.append(String.format(
                    "消息ID: %s | 用户ID: %d | 商品ID: %d | 发送状态: %d | 消费状态: %d | 发送重试: %d | 消费重试: %d | 原因: %s\n",
                    message.getMessageId(),
                    message.getUserId(),
                    message.getSeckillProductId(),
                    message.getStatus(),
                    message.getConsumeStatus(),
                    message.getRetryCount(),
                    message.getConsumeRetryCount(),
                    message.getFailReason() != null ? message.getFailReason() : "未知"
            ));
        }

        content.append("----------------------------------------\n");
        content.append("处理建议: 请人工检查数据库中对应用户的订单和库存状态，必要时手动补单或退还库存\n");

        // 1. 日志告警（始终执行）
        log.error("\n{}", content);

        // 2. 钉钉 Webhook 告警（如果配置了 webhook 地址）
        if (dingtalkWebhook != null && !dingtalkWebhook.isEmpty()) {
            sendDingtalkAlert(alertTitle, content.toString());
        }
    }

    /**
     * 发送库存异常告警
     *
     * @param seckillProductId 秒杀商品ID
     * @param userId           用户ID
     * @param reason           异常原因
     */
    public void alertStockAnomaly(Long seckillProductId, Long userId, String reason) {
        if (!alertEnabled) {
            return;
        }

        String alertContent = String.format(
                "【秒杀系统告警】库存异常\n告警时间: %s\n商品ID: %d\n用户ID: %d\n异常原因: %s\n处理建议: 请检查Redis和MySQL库存一致性",
                LocalDateTime.now().format(FORMATTER),
                seckillProductId,
                userId,
                reason
        );

        log.error("\n{}", alertContent);

        if (dingtalkWebhook != null && !dingtalkWebhook.isEmpty()) {
            sendDingtalkAlert("【秒杀系统告警】库存异常", alertContent);
        }
    }

    /**
     * 发送钉钉 Webhook 告警
     * <p>
     * 通过钉钉群机器人 Webhook 发送文本消息。
     * 配置方式：在 application.yml 中设置 alert.dingtalk.webhook
     * </p>
     */
    private void sendDingtalkAlert(String title, String content) {
        try {
            // 构建钉钉消息体
            String body = String.format(
                    "{\"msgtype\":\"text\",\"text\":{\"content\":\"%s\"}}",
                    content.replace("\"", "\\\"").replace("\n", "\\n")
            );

            // 使用 Java 原生 HttpClient 发送（避免额外依赖）
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(dingtalkWebhook))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("【告警通知】钉钉告警发送成功，标题: {}", title);
            } else {
                log.warn("【告警通知】钉钉告警发送失败，状态码: {}, 响应: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("【告警通知】钉钉告警发送异常，标题: {}", title, e);
        }
    }
}
