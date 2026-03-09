package com.example.sell.consumer;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
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
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 对话消息向量化消费者
 * 接收 RocketMQ 消息，计算 embedding 向量并存入 Milvus
 *
 * @author 屈轩
 */
@Slf4j
@Component
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

    @Resource
    private DashScopeEmbeddingModel embeddingModel;

    @Resource
    private MilvusClientV2 milvusClient;

    @Override
    public void onMessage(String message) {
        JSONObject msg = JSONUtil.parseObj(message);
        String sessionId = msg.getStr("sessionId");
        String role = msg.getStr("role");
        String content = msg.getStr("content");
        long timestamp = msg.getLong("timestamp", System.currentTimeMillis());

        log.info("[向量化] 开始处理, sessionId={}, role={}, 内容长度={}", sessionId, role, content.length());

        try {
            // 1. 调用 DashScope 计算 embedding 向量
            EmbeddingResponse response = embeddingModel.call(
                    new EmbeddingRequest(List.of(content), null));
            float[] vector = response.getResult().getOutput();

            // 2. 构建 Milvus 行数据
            JsonObject row = new JsonObject();
            row.addProperty("session_id", sessionId);
            row.addProperty("role", role);
            // 截断过长内容以适配 VarChar 长度限制
            String truncatedContent = content.length() > MAX_CONTENT_LENGTH
                    ? content.substring(0, MAX_CONTENT_LENGTH)
                    : content;
            row.addProperty("content", truncatedContent);
            row.addProperty("create_time", timestamp);

            JsonArray vectorArray = new JsonArray();
            for (float v : vector) {
                vectorArray.add(v);
            }
            row.add("content_vector", vectorArray);

            // 3. 插入 Milvus（指定数据库 sell）
            milvusClient.insert(InsertReq.builder()
                    .databaseName("sell")
                    .collectionName(COLLECTION_NAME)
                    .data(List.of(row))
                    .build());

            log.info("[向量化] 存储成功, sessionId={}, role={}, 向量维度={}", sessionId, role, vector.length);

        } catch (Exception e) {
            log.error("[向量化] 处理失败, sessionId={}, role={}", sessionId, role, e);
            // 向量化是辅助功能，不抛异常避免无限重试
            // 如果是临时性故障（如网络超时），可抛异常触发 RocketMQ 重试
        }
    }
}