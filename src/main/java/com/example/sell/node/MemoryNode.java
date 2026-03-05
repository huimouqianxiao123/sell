package com.example.sell.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.example.sell.service.Imp.AiChatMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 记忆加载节点
 * 从 Redis/MySQL 加载历史对话上下文，注入到工作流状态中
 *
 * 输入状态：StateKeys.SESSION_ID（会话ID）
 * 输出状态：StateKeys.MEMORY_CONTEXT（历史对话上下文文本）
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class MemoryNode implements NodeActionWithConfig {

    @Resource
    private AiChatMemoryService aiChatMemoryService;

    @Override
    public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
        String sessionId = state.value(StateKeys.SESSION_ID, String.class).orElse("");

        log.info("[记忆加载] 开始加载, sessionId={}", sessionId);

        // 调用 AiChatMemoryService 加载上下文
        String memoryContext = aiChatMemoryService.loadMemoryContext(sessionId);

        log.info("[记忆加载] 加载完成, sessionId={}, 上下文长度={}",
                sessionId, memoryContext != null ? memoryContext.length() : 0);

        return Map.of(StateKeys.MEMORY_CONTEXT, memoryContext != null ? memoryContext : "");
    }
}
