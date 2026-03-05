package com.example.sell.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 会话消息返回对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatMessageVo {

    private Long id;

    private String sessionId;

    private String role;

    private String eventType;

    private String content;

    private String extraJson;

    private LocalDateTime createTime;
}