package com.example.sell.ai.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryContextAssemblerTest {

    private final MemoryContextAssembler assembler = new MemoryContextAssembler();

    @Test
    void shouldAppendRelevantElasticMemoryAfterShortTermContext() {
        String context = assembler.assemble(
                "用户: 我想买耳机\n助手: 可以看看降噪款",
                List.of(AiSearchCandidate.builder()
                        .sourceType("memory")
                        .source("ai_chat_message")
                        .title("会话记忆(user)")
                        .content("用户偏好预算在500元以内的蓝牙耳机。")
                        .keywordScore(6.0)
                        .finalScore(0.9)
                        .build()),
                500
        );

        assertTrue(context.contains("[短期对话记忆]"));
        assertTrue(context.contains("[相关历史记忆]"));
        assertTrue(context.contains("500元以内的蓝牙耳机"));
    }

    @Test
    void shouldRespectMaxCharsWhenMemoryIsLong() {
        String context = assembler.assemble(
                "用户: A",
                List.of(AiSearchCandidate.builder()
                        .sourceType("memory")
                        .source("ai_chat_message")
                        .title("会话记忆(user)")
                        .content("这是一段非常长的历史记忆".repeat(50))
                        .build()),
                120
        );

        assertTrue(context.length() <= 120);
        assertFalse(context.isBlank());
    }
}
