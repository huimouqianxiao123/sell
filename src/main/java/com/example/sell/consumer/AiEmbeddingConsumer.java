package com.example.sell.consumer;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.example.sell.ai.search.ElasticAiSearchService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * AI 用户画像摘要向量化消费者
 * 接收 RocketMQ 消息（包含多轮对话历史），调用 LLM 提取用户个性化摘要，
 * 计算 embedding 向量并存入 Milvus，用于后续语义检索和个性化推荐。
 *
 * @author 屈轩
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq.listener", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "ai-embedding-topic",
        consumerGroup = "ai-embedding-group",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING,
        maxReconsumeTimes = 3
)
public class AiEmbeddingConsumer implements RocketMQListener<String> {

    /** Milvus 集合名 */
    private static final String COLLECTION_NAME = "chat_message";

    /** 内容最大长度（适配 Milvus VarChar 限制） */
    private static final int MAX_CONTENT_LENGTH = 8000;

    /** 无效摘要标识 */
    private static final String NO_VALID_INFO = "无有效用户信息";

    @Resource
    private DashScopeEmbeddingModel embeddingModel;

    @Lazy
    @Resource
    private MilvusClientV2 milvusClient;

    @Resource(name = "summaryModel")
    private ChatModel summaryModel;

    @Resource
    private ElasticAiSearchService elasticAiSearchService;

    @Override
    public void onMessage(String message) {
        JSONObject msg = JSONUtil.parseObj(message);

        // 新格式：包含 conversationHistory 字段（20 轮对话触发）
        String conversationHistory = msg.getStr("conversationHistory");
        if (StringUtils.hasText(conversationHistory)) {
            handleProfileExtraction(msg);
            return;
        }

        // 兼容旧格式（直接存储原始消息）
        if (msg.containsKey("role") && msg.containsKey("content")) {
            handleLegacyMessage(msg);
        }
    }

    /**
     * 新流程：从多轮对话中提取用户画像摘要，向量化后存入 Milvus
     */
    private void handleProfileExtraction(JSONObject msg) {
        String sessionId = msg.getStr("sessionId");
        String userId = msg.getStr("userId");
        String conversationHistory = msg.getStr("conversationHistory");
        long timestamp = msg.getLong("timestamp", System.currentTimeMillis());

        log.info("[用户画像] 开始提取, sessionId={}, userId={}, 对话长度={}",
                sessionId, userId, conversationHistory.length());

        try {
            // 1. 调用 LLM 提取用户个性化摘要
            String profileSummary = extractUserProfile(conversationHistory);

            // 2. 无有效信息则跳过
            if (!StringUtils.hasText(profileSummary) || profileSummary.contains(NO_VALID_INFO)) {
                log.info("[用户画像] 本轮对话无有效用户信息，跳过向量化, sessionId={}", sessionId);
                return;
            }

            // 3. 计算摘要的 embedding 向量
            EmbeddingResponse response = embeddingModel.call(
                    new EmbeddingRequest(List.of(profileSummary), null));
            float[] vector = response.getResult().getOutput();

            // 4. 构建 Milvus 行数据
            JsonObject row = new JsonObject();
            row.addProperty("session_id", sessionId);
            row.addProperty("role", "summary");
            String truncated = profileSummary.length() > MAX_CONTENT_LENGTH
                    ? profileSummary.substring(0, MAX_CONTENT_LENGTH)
                    : profileSummary;
            row.addProperty("content", truncated);
            row.addProperty("create_time", timestamp);

            JsonArray vectorArray = new JsonArray();
            for (float v : vector) {
                vectorArray.add(v);
            }
            row.add("content_vector", vectorArray);

            // 5. 插入 Milvus
            milvusClient.insert(InsertReq.builder()
                    .databaseName("sell")
                    .collectionName(COLLECTION_NAME)
                    .data(List.of(row))
                    .build());

            elasticAiSearchService.indexMemoryText(
                    "profile:" + sessionId + ":" + timestamp,
                    userId,
                    sessionId,
                    "summary",
                    "profile",
                    profileSummary,
                    timestamp
            );

            log.info("[用户画像] 摘要存储成功, sessionId={}, userId={}, 摘要长度={}, 向量维度={}",
                    sessionId, userId, profileSummary.length(), vector.length);

        } catch (Exception e) {
            log.error("[用户画像] 提取失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 调用 qwen-turbo 从对话历史中提取用户个性化信息摘要
     */
    private String extractUserProfile(String conversationHistory) {
        String promptText = "请根据以下用户与AI助手的多轮对话，提取关于用户的个性化信息摘要。\n" +
                "重点提取：\n" +
                "1. 用户的兴趣偏好（如商品类型、价格区间、品牌偏好等）\n" +
                "2. 用户的购物需求和目的\n" +
                "3. 用户的沟通风格和特点\n" +
                "4. 其他有价值的用户特征\n\n" +
                "对话历史：\n" + conversationHistory + "\n\n" +
                "请用简洁的语句输出用户画像摘要，不超过300字。" +
                "如果对话中没有有价值的用户信息（例如全是闲聊），请只输出\"无有效用户信息\"。";

        Prompt prompt = new Prompt(promptText);
        ChatResponse chatResponse = summaryModel.call(prompt);
        String content = chatResponse.getResult().getOutput().getText();
        return content != null ? content.trim() : "";
    }

    /**
     * 兼容旧格式消息：直接对原始内容计算 embedding 并存入 Milvus
     */
    private void handleLegacyMessage(JSONObject msg) {
        String sessionId = msg.getStr("sessionId");
        String role = msg.getStr("role");
        String content = msg.getStr("content");
        long timestamp = msg.getLong("timestamp", System.currentTimeMillis());

        log.info("[向量化] 处理旧格式消息, sessionId={}, role={}, 内容长度={}",
                sessionId, role, content.length());

        try {
            EmbeddingResponse response = embeddingModel.call(
                    new EmbeddingRequest(List.of(content), null));
            float[] vector = response.getResult().getOutput();

            JsonObject row = new JsonObject();
            row.addProperty("session_id", sessionId);
            row.addProperty("role", role);
            String truncated = content.length() > MAX_CONTENT_LENGTH
                    ? content.substring(0, MAX_CONTENT_LENGTH)
                    : content;
            row.addProperty("content", truncated);
            row.addProperty("create_time", timestamp);

            JsonArray vectorArray = new JsonArray();
            for (float v : vector) {
                vectorArray.add(v);
            }
            row.add("content_vector", vectorArray);

            milvusClient.insert(InsertReq.builder()
                    .databaseName("sell")
                    .collectionName(COLLECTION_NAME)
                    .data(List.of(row))
                    .build());

            elasticAiSearchService.indexMemoryText(
                    "legacy:" + sessionId + ":" + role + ":" + timestamp,
                    null,
                    sessionId,
                    role,
                    "message",
                    truncated,
                    timestamp
            );

            log.info("[向量化] 旧格式存储成功, sessionId={}, role={}", sessionId, role);
        } catch (Exception e) {
            log.error("[向量化] 旧格式处理失败, sessionId={}, role={}", sessionId, role, e);
        }
    }
}
