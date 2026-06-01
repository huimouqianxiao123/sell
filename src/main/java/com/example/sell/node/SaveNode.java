package com.example.sell.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.example.sell.service.impl.AiChatMemoryService;
import com.example.sell.service.impl.AiChatPersistenceService;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 持久化节点
 * 将 Agent 回答保存到 MySQL 并更新 Redis 缓存。
 *
 * 架构设计：
 * - 原始对话 → JDBC 永久保留（完整审计）
 * - 每 10 轮增量提炼 → 用户画像摘要 → VectorStore（语义检索）
 * - 短期记忆 → 滑动窗口始终可用（AiChatMemoryService）
 * - Redis 职责 → 仅做计数触发器
 * - 异步处理 → 不影响响应速度
 *
 * 仅在非 HITL 中断时执行保存。
 *
 * 输入状态：SESSION_ID、QUESTION、AGENT_RESULT、HITL_INTERRUPTED、USER_ID
 * 输出状态：无（终端节点）
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class SaveNode implements NodeActionWithConfig {

    private static final String EMBEDDING_TOPIC = "ai-embedding-topic";

    /** 对话轮次计数器 Redis 键前缀 */
    private static final String KEY_CHAT_ROUND = "ai:chat:round:";

    /** 触发摘要提取的对话轮次阈值（每 10 轮增量提炼） */
    private static final int SUMMARY_TRIGGER_ROUNDS = 10;

    /** 计数器过期时间（小时），与会话生命周期对齐 */
    private static final long ROUND_COUNTER_TTL_HOURS = 24;

    /** 从 MySQL 加载的消息条数（10 轮 × 2 条/轮） */
    private static final int HISTORY_LOAD_LIMIT = 20;

    @Resource
    private AiChatPersistenceService aiChatPersistenceService;

    @Resource
    private AiChatMemoryService aiChatMemoryService;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
        String sessionId = state.value(StateKeys.SESSION_ID, String.class).orElse("");
        String question = state.value(StateKeys.QUESTION, String.class).orElse("");
        String agentResult = state.value(StateKeys.AGENT_RESULT, String.class).orElse("");
        boolean hitlInterrupted = state.value(StateKeys.HITL_INTERRUPTED, Boolean.class).orElse(false);
        String userId = state.value(StateKeys.USER_ID, String.class).orElse("");

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
            aiChatPersistenceService.saveAssistantMessage(sessionId, userId, agentResult);

            // 2. 追加消息到 Redis 缓存
            aiChatMemoryService.appendMessage(sessionId, "assistant", agentResult);

            log.info("[持久化] 保存完成, sessionId={}, 回答长度={}", sessionId, agentResult.length());

            // 3. 累计对话轮次，达到阈值时触发用户画像摘要提取
            checkAndTriggerProfileExtraction(sessionId, userId);

        } catch (Exception e) {
            log.error("[持久化] 保存失败, sessionId={}", sessionId, e);
        }

        return Map.of();
    }

    /**
     * 递增对话轮次计数器，达到阈值时触发用户画像摘要提取。
     * 每完成一轮对话（用户提问 + AI 回答）计数 +1，
     * 累积到 10 轮后加载历史对话发送到 RocketMQ，异步提炼摘要。
     *
     * 设计要点：
     * - 每 10 轮增量提炼，不影响原始对话的完整保留
     * - 异步处理，不阻塞用户响应
     * - Redis 仅做计数触发器，不存储对话内容
     */
    private void checkAndTriggerProfileExtraction(String sessionId, String userId) {
        try {
            String counterKey = KEY_CHAT_ROUND + sessionId;

            // 原子递增，返回递增后的值
            Long currentRound = stringRedisTemplate.opsForValue().increment(counterKey);
            if (currentRound == null) {
                return;
            }

            // 首次创建时设置过期时间
            if (currentRound == 1) {
                stringRedisTemplate.expire(counterKey, ROUND_COUNTER_TTL_HOURS, TimeUnit.HOURS);
            }

            log.debug("[用户画像] 对话轮次: {}/{}, sessionId={}", currentRound, SUMMARY_TRIGGER_ROUNDS, sessionId);

            if (currentRound >= SUMMARY_TRIGGER_ROUNDS) {
                log.info("[用户画像] 达到 {} 轮阈值，触发增量摘要提取, sessionId={}", SUMMARY_TRIGGER_ROUNDS, sessionId);

                // 加载最近的对话历史（增量提炼，原文保留在 JDBC）
                String conversationHistory = loadConversationHistory(sessionId);
                if (StringUtils.hasText(conversationHistory)) {
                    sendProfileExtractionMessage(sessionId, userId, conversationHistory);
                }

                // 重置计数器，开始下一个 10 轮周期
                stringRedisTemplate.opsForValue().set(counterKey, "0", ROUND_COUNTER_TTL_HOURS, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            // 画像提取是辅助功能，失败不影响核心流程
            log.error("[用户画像] 轮次计数或触发失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 从 MySQL 加载最近的对话历史，格式化为文本
     */
    private String loadConversationHistory(String sessionId) {
        try {
            List<com.example.sell.entity.AiChatMessage> messages =
                    aiChatPersistenceService.findRecentMessages(sessionId, HISTORY_LOAD_LIMIT);
            if (messages == null || messages.isEmpty()) {
                return "";
            }

            // findRecentBySessionId 返回 DESC，需要反转为时间正序
            java.util.Collections.reverse(messages);

            StringBuilder sb = new StringBuilder();
            for (com.example.sell.entity.AiChatMessage msg : messages) {
                if (!"message".equals(msg.getEventType())) {
                    continue;
                }
                String role = msg.getRole();
                if ("user".equals(role)) {
                    sb.append("用户: ").append(msg.getContent()).append("\n");
                } else if ("assistant".equals(role)) {
                    sb.append("助手: ").append(msg.getContent()).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("[用户画像] 加载对话历史失败, sessionId={}", sessionId, e);
            return "";
        }
    }

    /**
     * 异步发送用户画像摘要提取请求到 RocketMQ
     * 消费者将调用 LLM 生成个性化摘要，再计算 embedding 存入 Milvus
     */
    private void sendProfileExtractionMessage(String sessionId, String userId, String conversationHistory) {
        try {
            String message = JSONUtil.createObj()
                    .set("sessionId", sessionId)
                    .set("userId", userId)
                    .set("conversationHistory", conversationHistory)
                    .set("timestamp", System.currentTimeMillis())
                    .toString();

            rocketMQTemplate.asyncSend(EMBEDDING_TOPIC, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.info("[用户画像] 摘要提取请求发送成功, sessionId={}", sessionId);
                }

                @Override
                public void onException(Throwable e) {
                    log.error("[用户画像] 摘要提取请求发送失败, sessionId={}", sessionId, e);
                }
            });
        } catch (Exception e) {
            log.error("[用户画像] 消息构建失败, sessionId={}", sessionId, e);
        }
    }
}
