package com.example.sell.domain.Dto;

import lombok.Data;

/**
 * 商品 AI 客服请求参数
 */
@Data
public class AiProductChatRequest {

    /**
     * 会话ID，前端首次可不传，后端会生成并通过流事件返回
     */
    private String sessionId;

    /**
     * 用户自然语言输入
     */
    private String message;

    /**
     * HITL 确认标识：
     * null = 正常聊天请求
     * true = 用户确认执行工具调用
     * false = 用户取消工具调用
     */
    private Boolean hitlApproved;
}
