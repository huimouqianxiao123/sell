package com.example.sell.service.Imp;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.sell.dao.AiChatMessageMapper;
import com.example.sell.domain.pojo.AiChatMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI 会话持久化组件
 * 1. Redis：会话活跃标记 + HITL 待确认状态
 * 2. MySQL：会话消息与关键事件审计
 */
@Slf4j
@Component
public class AiChatPersistenceService {

    private static final String KEY_AI_HITL_PENDING = "ai:hitl:pending:";
    private static final String KEY_AI_SESSION_ACTIVE = "ai:session:active:";
    private static final String KEY_AI_SUMMARY_HASH = "ai:summary:hash:";
    // sessionId → 当前有效 threadId（消息链损坏修复后会更换）
    private static final String KEY_AI_SESSION_THREADID = "ai:session:threadid:";
    // sessionId → HITL 中断前的干净对话历史（纯文本，供恢复时注入上下文）
    private static final String KEY_AI_PRE_HITL_HISTORY = "ai:pre_hitl_history:";
    private static final long SESSION_TTL_HOURS = 24;
    private static final long HITL_TTL_MINUTES = 10;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AiChatMessageMapper aiChatMessageMapper;

    // ==================== 会话管理 ====================

    public void touchSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    KEY_AI_SESSION_ACTIVE + sessionId,
                    String.valueOf(System.currentTimeMillis()),
                    SESSION_TTL_HOURS,
                    TimeUnit.HOURS
            );
        } catch (Exception e) {
            log.warn("[AI会话] 刷新 Redis 会话活跃时间失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 持久化 sessionId 对应的有效 threadId。
     * 当消息链损坏被修复（freshThreadId）或 HITL 遗留状态被清除时调用，
     * 确保后续对话不会再加载损坏的 RedisSaver 状态。
     */
    public void saveActiveThreadId(String sessionId, String threadId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(threadId)) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    KEY_AI_SESSION_THREADID + sessionId,
                    threadId,
                    SESSION_TTL_HOURS,
                    TimeUnit.HOURS
            );
            log.info("[AI会话] 更新有效 threadId, sessionId={}, threadId={}", sessionId, threadId);
        } catch (Exception e) {
            log.warn("[AI会话] 保存有效 threadId 失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 获取 sessionId 当前有效的 threadId。
     * 若未曾切换过，返回 null（调用方应回退为 sessionId 本身）。
     */
    public String getActiveThreadId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        try {
            return stringRedisTemplate.opsForValue().get(KEY_AI_SESSION_THREADID + sessionId);
        } catch (Exception e) {
            log.warn("[AI会话] 获取有效 threadId 失败, sessionId={}", sessionId, e);
            return null;
        }
    }

    // ==================== HITL 状态管理 ====================

    /**
     * 保存 HITL 待确认状态到 Redis
     *
     * @param sessionId       会话ID
     * @param originalMessage 用户原始消息
     * @param feedbacks       待调用工具的反馈列表（toolName, arguments, description）
     */
    public void savePendingHitl(String sessionId, String originalMessage, List<Map<String, Object>> feedbacks) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            obj.set("originalMessage", originalMessage);
            obj.set("feedbacks", feedbacks);
            obj.set("timestamp", System.currentTimeMillis());

            stringRedisTemplate.opsForValue().set(
                    KEY_AI_HITL_PENDING + sessionId,
                    obj.toString(),
                    HITL_TTL_MINUTES,
                    TimeUnit.MINUTES
            );
            log.info("[AI会话] HITL 待确认状态已保存, sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("[AI会话] 保存 HITL 待确认状态失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 读取并消费 HITL 待确认状态（读取后自动删除）
     *
     * @return JSON 对象，包含 originalMessage、feedbacks、timestamp；不存在或过期返回 null
     */
    public JSONObject consumePendingHitl(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String key = KEY_AI_HITL_PENDING + sessionId;
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            stringRedisTemplate.delete(key);
            if (json == null) {
                return null;
            }
            return JSONUtil.parseObj(json);
        } catch (Exception e) {
            log.error("[AI会话] 读取 HITL 待确认状态失败, sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 检查是否存在未处理的 HITL 待确认状态（不消费，仅探测）
     */
    public boolean hasPendingHitl(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(
                    stringRedisTemplate.hasKey(KEY_AI_HITL_PENDING + sessionId));
        } catch (Exception e) {
            log.warn("[AI会话] 检查 HITL 待确认状态失败, sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * 清除 HITL 待确认状态（用户取消时调用）
     */
    public void clearPendingHitl(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            stringRedisTemplate.delete(KEY_AI_HITL_PENDING + sessionId);
        } catch (Exception e) {
            log.warn("[AI会话] 清除 HITL 待确认状态失败, sessionId={}", sessionId, e);
        }
    }

    // ==================== HITL 前对话历史 ====================

    /**
     * 保存 HITL 中断前的干净对话历史（不含末尾含 tool_calls 的 AssistantMessage）。
     * 以纯文本形式存储，供 HITL 恢复时注入到新对话上下文，避免用户历史丢失。
     *
     * @param sessionId   会话ID
     * @param historyText 格式化后的对话历史文本（"用户: ...\n助手: ...\n"）
     */
    public void savePreHitlHistory(String sessionId, String historyText) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(historyText)) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    KEY_AI_PRE_HITL_HISTORY + sessionId,
                    historyText,
                    HITL_TTL_MINUTES,
                    TimeUnit.MINUTES
            );
            log.info("[AI会话] HITL 前对话历史已保存, sessionId={}, 长度={}", sessionId, historyText.length());
        } catch (Exception e) {
            log.warn("[AI会话] 保存 HITL 前对话历史失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 读取并消费 HITL 前对话历史（读取后自动删除）。
     *
     * @return 对话历史文本，不存在或已过期返回 null
     */
    public String consumePreHitlHistory(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String key = KEY_AI_PRE_HITL_HISTORY + sessionId;
        try {
            String history = stringRedisTemplate.opsForValue().get(key);
            stringRedisTemplate.delete(key);
            return history;
        } catch (Exception e) {
            log.warn("[AI会话] 读取 HITL 前对话历史失败, sessionId={}", sessionId, e);
            return null;
        }
    }

    // ==================== 摘要持久化 ====================

    /**
     * 保存 SummarizationHook 生成的摘要到数据库。
     * 通过 Redis 缓存内容哈希进行去重，内容未变化时直接跳过，避免重复写入。
     *
     * @param sessionId      会话ID
     * @param summaryContent 摘要全文（含 summaryPrefix 前缀）
     */
    public void saveSummaryIfChanged(String sessionId, String summaryContent) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(summaryContent)) {
            return;
        }
        String hashKey = KEY_AI_SUMMARY_HASH + sessionId;
        String contentHash = String.valueOf(summaryContent.hashCode());
        try {
            String lastHash = stringRedisTemplate.opsForValue().get(hashKey);
            if (contentHash.equals(lastHash)) {
                log.debug("[AI摘要] 摘要内容未变化，跳过写入, sessionId={}", sessionId);
                return;
            }
            insertMessage(sessionId, "system", "summary", summaryContent, null);
            stringRedisTemplate.opsForValue().set(
                    hashKey, contentHash, SESSION_TTL_HOURS, TimeUnit.HOURS);
            log.info("[AI摘要] 摘要已持久化到数据库, sessionId={}, 长度={}", sessionId, summaryContent.length());
        } catch (Exception e) {
            log.error("[AI摘要] 保存摘要失败, sessionId={}", sessionId, e);
        }
    }

    // ==================== 消息持久化 ====================

    public void saveUserMessage(String sessionId, String content) {
        insertMessage(sessionId, "user", "message", content, null);
    }

    public void saveAssistantMessage(String sessionId, String content) {
        insertMessage(sessionId, "assistant", "message", content, null);
    }

    public void saveSystemEvent(String sessionId, String eventType, String content, Map<String, Object> extra) {
        insertMessage(sessionId, "system", eventType, content, extra);
    }

    public List<AiChatMessage> findRecentMessages(String sessionId, int limit) {
        if (!StringUtils.hasText(sessionId) || limit <= 0) {
            return Collections.emptyList();
        }
        try {
            return aiChatMessageMapper.findRecentBySessionId(sessionId, limit);
        } catch (Exception e) {
            log.error("[AI会话] 查询历史消息失败, sessionId={}", sessionId, e);
            return Collections.emptyList();
        }
    }

    private void insertMessage(String sessionId, String role, String eventType, String content, Map<String, Object> extra) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            AiChatMessage message = AiChatMessage.builder()
                    .sessionId(sessionId)
                    .role(role)
                    .eventType(eventType)
                    .content(content)
                    .extraJson(extra == null ? null : JSONUtil.toJsonStr(extra))
                    .build();
            aiChatMessageMapper.insert(message);
        } catch (Exception e) {
            log.error("[AI会话] 写入 MySQL 聊天消息失败, sessionId={}, eventType={}", sessionId, eventType, e);
        }
    }
}
