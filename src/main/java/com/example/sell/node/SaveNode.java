package com.example.sell.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.example.sell.service.Imp.AiChatMemoryService;
import com.example.sell.service.Imp.AiChatPersistenceService;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 持久化节点
 * 将 Agent 回答保存到 MySQL 并更新 Redis 缓存
 * 同时通过 RocketMQ 异步发送消息，计算 embedding 并存入 Milvus 向量数据库
 * 仅在非 HITL 中断时执行保存（HITL 中断的消息由 Service 层在恢复时处理）
 *
 * 输入状态：SESSION_ID、QUESTION、AGENT_RESULT、HITL_INTERRUPTED
 * 输出状态：无（终端节点）
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class SaveNode implements NodeActionWithConfig {

    private static final String EMBEDDING_TOPIC = "ai-embedding-topic";

    @Resource
    private AiChatPersistenceService aiChatPersistenceService;

    @Resource
    private AiChatMemoryService aiChatMemoryService;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Override
    public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
        String sessionId = state.value(StateKeys.SESSION_ID, String.class).orElse("");
        String question = state.value(StateKeys.QUESTION, String.class).orElse("");
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

            // 3. 异步发送向量化消息到 RocketMQ（用户问题 + AI 回答）
            if (StringUtils.hasText(question)) {
                sendEmbeddingMessage(sessionId, "user", question);
            }
            sendEmbeddingMessage(sessionId, "assistant", agentResult);

        } catch (Exception e) {
            log.error("[持久化] 保存失败, sessionId={}", sessionId, e);
        }

        return Map.of();
    }

    /**
     * 异步发送向量化消息到 RocketMQ
     * 消费者将计算 embedding 并存入 Milvus
     */
    private void sendEmbeddingMessage(String sessionId, String role, String content) {
        try {
            String message = JSONUtil.createObj()
                    .set("sessionId", sessionId)
                    .set("role", role)
                    .set("content", content)
                    .set("timestamp", System.currentTimeMillis())
                    .toString();

            rocketMQTemplate.asyncSend(EMBEDDING_TOPIC, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.debug("[向量化] 消息发送成功, sessionId={}, role={}", sessionId, role);
                }

                @Override
                public void onException(Throwable e) {
                    log.error("[向量化] 消息发送失败, sessionId={}, role={}", sessionId, role, e);
                }
            });
        } catch (Exception e) {
            // 向量化是辅助功能，失败不影响核心流程
            log.error("[向量化] 消息构建失败, sessionId={}", sessionId, e);
        }
    }
}
