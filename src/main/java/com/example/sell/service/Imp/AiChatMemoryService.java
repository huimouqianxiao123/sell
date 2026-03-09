package com.example.sell.service.Imp;

import com.example.sell.dao.AiChatMessageMapper;
import com.example.sell.domain.pojo.AiChatMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI 对话记忆管理服务
 * 负责对话上下文的加载、缓存、Token 估算和小模型压缩
 *
 * 缓存策略：
 * - Redis 优先 → MySQL 回退 → Token 估算 → 超限压缩
 *
 * Redis 键空间：
 * - ai:chat:messages:{sessionId}  — 对话消息列表 JSON（TTL 24h）
 * - ai:chat:summary:{sessionId}   — 压缩后的摘要文本（TTL 24h）
 *
 * @author 屈轩
 */
@Slf4j
@Service
public class AiChatMemoryService {

    /** 对话消息缓存键前缀 */
    private static final String KEY_CHAT_MESSAGES = "ai:chat:messages:";

    /** 压缩摘要缓存键前缀 */
    private static final String KEY_CHAT_SUMMARY = "ai:chat:summary:";

    /** 缓存过期时间（小时） */
    private static final long CACHE_TTL_HOURS = 24;

    /** 从 MySQL 加载的最近消息条数 */
    private static final int RECENT_MESSAGE_LIMIT = 14;

    /** Token 上限，超过则触发压缩 */
    private static final int MAX_TOKENS = 8000;

    @Resource
    private RedisTemplate<String, Object>redisTemplate;

    @Resource
    private AiChatMessageMapper aiChatMessageMapper;

    @Resource
    private ChatClient chatClient;

    /**
     * 加载对话上下文（主入口）
     *
     * 流程：
     * 1. 先从 Redis 缓存加载压缩摘要（如有）
     * 2. 再从 Redis 缓存加载消息列表
     * 3. Redis 没有 → 从 MySQL 查询最近消息并写入缓存
     * 4. 格式化为文本 "用户: ...\n助手: ...\n"
     * 5. 估算 Token，超限则调用小模型压缩
     *
     * @param sessionId 会话ID
     * @return 格式化后的对话上下文文本
     */
    public String loadMemoryContext(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return "";
        }

        try {
            // 1. 优先检查是否有压缩摘要
            String cachedSummary = getCachedSummary(sessionId);
            if (StringUtils.hasText(cachedSummary)) {
                log.info("[AI记忆] 使用缓存摘要, sessionId={}, 长度={}", sessionId, cachedSummary.length());
                return cachedSummary;
            }

            // 2. 从 Redis 缓存或 MySQL 加载消息
            String messagesText = loadMessagesText(sessionId);
            if (!StringUtils.hasText(messagesText)) {
                log.info("[AI记忆] 无历史消息, sessionId={}", sessionId);
                return "";
            }

            // 3. Token 估算，超限则压缩
            int estimatedTokens = estimateTokens(messagesText);
            log.info("[AI记忆] 上下文 Token 估算: {}, sessionId={}", estimatedTokens, sessionId);

            if (estimatedTokens > MAX_TOKENS) {
                log.info("[AI记忆] Token 超限({}>{})，执行压缩, sessionId={}",
                        estimatedTokens, MAX_TOKENS, sessionId);
                String compressed = compressContext(messagesText);
                // 缓存压缩结果
                cacheSummary(sessionId, compressed);
                return compressed;
            }

            return messagesText;
        } catch (Exception e) {
            log.error("[AI记忆] 加载对话上下文失败, sessionId={}", sessionId, e);
            return "";
        }
    }

    /**
     * 追加消息到 Redis 缓存
     * 在已有缓存的基础上追加新消息，保持缓存与实际对话同步
     *
     * @param sessionId 会话ID
     * @param role      角色（user/assistant）
     * @param content   消息内容
     */
    public void appendMessage(String sessionId, String role, String content) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(content)) {
            return;
        }
        try {
            String key = KEY_CHAT_MESSAGES + sessionId;
            Object raw = redisTemplate.opsForValue().get(key);
            String existing = raw != null ? raw.toString() : null;

            String roleLabel = "user".equals(role) ? "用户" : "助手";
            String newLine = roleLabel + ": " + content + "\n";

            String updated = StringUtils.hasText(existing)
                    ? existing + newLine
                    : newLine;

            redisTemplate.opsForValue().set(key, updated, CACHE_TTL_HOURS, TimeUnit.HOURS);

            // 追加消息后清除旧的压缩摘要（因为上下文已变化）
            evictSummary(sessionId);
        } catch (Exception e) {
            log.warn("[AI记忆] 追加消息到缓存失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 清除指定会话的所有缓存
     *
     * @param sessionId 会话ID
     */
    public void evictCache(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            redisTemplate.delete(KEY_CHAT_MESSAGES + sessionId);
            redisTemplate.delete(KEY_CHAT_SUMMARY + sessionId);
            log.info("[AI记忆] 缓存已清除, sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("[AI记忆] 清除缓存失败, sessionId={}", sessionId, e);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 从 Redis 缓存或 MySQL 加载消息并格式化为文本
     */
    private String loadMessagesText(String sessionId) {
        // 先尝试 Redis 缓存
        String cached = getCachedMessages(sessionId);
        if (StringUtils.hasText(cached)) {
            log.debug("[AI记忆] 命中 Redis 消息缓存, sessionId={}", sessionId);
            return cached;
        }

        // 回退到 MySQL
        List<AiChatMessage> messages = loadFromDatabase(sessionId);
        if (messages.isEmpty()) {
            return "";
        }

        // 格式化为文本
        String formatted = formatMessages(messages);

        // 写入 Redis 缓存
        cacheMessages(sessionId, formatted);

        return formatted;
    }

    /**
     * 从 MySQL 加载最近消息
     */
    private List<AiChatMessage> loadFromDatabase(String sessionId) {
        try {
            List<AiChatMessage> messages = aiChatMessageMapper.findRecentBySessionId(
                    sessionId, RECENT_MESSAGE_LIMIT);
            if (messages == null) {
                return Collections.emptyList();
            }
            log.info("[AI记忆] 从 MySQL 加载 {} 条消息, sessionId={}", messages.size(), sessionId);
            // findRecentBySessionId 是 DESC 排序，需要反转为时间正序
            Collections.reverse(messages);
            return messages;
        } catch (Exception e) {
            log.error("[AI记忆] 从 MySQL 加载消息失败, sessionId={}", sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 将消息列表格式化为 "用户: ...\n助手: ...\n" 格式文本
     */
    private String formatMessages(List<AiChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (AiChatMessage msg : messages) {
            // 只格式化用户和助手的普通消息
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
    }

    /**
     * Token 估算：中文约 2 字符/token
     */
    private int estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return text.length() / 2;
    }

    /**
     * 调用小模型压缩对话上下文
     */
    private String compressContext(String context) {
        try {
            String compressPrompt = "请将以下对话历史压缩为简洁的摘要，保留关键信息和用户意图，" +
                    "去除寒暄和重复内容。摘要应当让另一个AI助手能够继续对话。\n\n" +
                    "对话历史：\n" + context;

            String summary = chatClient.prompt()
                    .user(compressPrompt)
                    .call()
                    .content();

            log.info("[AI记忆] 上下文压缩完成, 原长度={}, 压缩后长度={}",
                    context.length(), summary != null ? summary.length() : 0);
            return summary != null ? summary : context;
        } catch (Exception e) {
            log.error("[AI记忆] 上下文压缩失败，返回原始文本", e);
            return context;
        }
    }

    // ==================== Redis 缓存操作 ====================

    private String getCachedMessages(String sessionId) {
        try {
            Object raw = redisTemplate.opsForValue().get(KEY_CHAT_MESSAGES + sessionId);
            return raw != null ? raw.toString() : null;
        } catch (Exception e) {
            log.warn("[AI记忆] 读取消息缓存失败, sessionId={}", sessionId, e);
            return null;
        }
    }

    private void cacheMessages(String sessionId, String text) {
        try {
            redisTemplate.opsForValue().set(
                    KEY_CHAT_MESSAGES + sessionId, text,
                    CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[AI记忆] 写入消息缓存失败, sessionId={}", sessionId, e);
        }
    }

    private String getCachedSummary(String sessionId) {
        try {
            Object raw = redisTemplate.opsForValue().get(KEY_CHAT_SUMMARY + sessionId);
            return raw != null ? raw.toString() : null;
        } catch (Exception e) {
            log.warn("[AI记忆] 读取摘要缓存失败, sessionId={}", sessionId, e);
            return null;
        }
    }

    private void cacheSummary(String sessionId, String summary) {
        try {
         redisTemplate.opsForValue().set(
                    KEY_CHAT_SUMMARY + sessionId, summary,
                    CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[AI记忆] 写入摘要缓存失败, sessionId={}", sessionId, e);
        }
    }

    private void evictSummary(String sessionId) {
        try {
            redisTemplate.delete(KEY_CHAT_SUMMARY + sessionId);
        } catch (Exception e) {
            log.warn("[AI记忆] 清除摘要缓存失败, sessionId={}", sessionId, e);
        }
    }
}
