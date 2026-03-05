package com.example.sell.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.example.sell.service.Imp.AiChatMemoryService;
import com.example.sell.service.Imp.AiChatPersistenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 持久化节点
 * 将 Agent 回答保存到 MySQL 并更新 Redis 缓存
 * 仅在非 HITL 中断时执行保存（HITL 中断的消息由 Service 层在恢复时处理）
 *
 * 输入状态：SESSION_ID、AGENT_RESULT、HITL_INTERRUPTED
 * 输出状态：无（终端节点）
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class SaveNode implements NodeActionWithConfig {

    @Resource
    private AiChatPersistenceService aiChatPersistenceService;

    @Resource
    private AiChatMemoryService aiChatMemoryService;

    @Override
    public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
        String sessionId = state.value(StateKeys.SESSION_ID, String.class).orElse("");
        String agentResult = state.value(StateKeys.AGENT_RESULT, String.class).orElse("");
        boolean hitlInterrupted = state.value(StateKeys.HITL_INTERRUPTED, Boolean.class).orElse(false);

        log.info("[持久化] 开始保存, sessionId={}, HITL中断={}", sessionId, hitlInterrupted);

        // HITL 中断时不保存 assistant 消息（此时还没有最终回答）
        if (hitlInterrupted) {
            log.info("[持久化] HITL 中断，跳过 assistant 消息保存, sessionId={}", sessionId);
            return Map.of();
        }

        if (!StringUtils.hasText(agentResult)) {
            log.warn("[持久化] Agent 回答为空，跳过保存, sessionId={}", sessionId);
            return Map.of();
        }

        try {
            // 1. 保存 AI 回复到 MySQL
            aiChatPersistenceService.saveAssistantMessage(sessionId, agentResult);

            // 2. 追加消息到 Redis 缓存
            aiChatMemoryService.appendMessage(sessionId, "assistant", agentResult);

            log.info("[持久化] 保存完成, sessionId={}, 回答长度={}", sessionId, agentResult.length());
        } catch (Exception e) {
            log.error("[持久化] 保存失败, sessionId={}", sessionId, e);
        }

        return Map.of();
    }
}
