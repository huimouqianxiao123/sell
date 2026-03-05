package com.example.sell.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI 会话历史返回对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatHistoryVo {

    private String sessionId;

    private Integer total;

    private List<AiChatMessageVo> list;
}