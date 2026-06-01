package com.example.sell.consumer;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.example.sell.ai.search.ElasticAiSearchService;
import com.example.sell.dao.KnowledgeDocMapper;
import com.example.sell.entity.KnowledgeDoc;
import com.example.sell.service.impl.PdfService;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * 知识库文档处理消费者
 * 流程：下载文件 → OCR/解析 → 大模型语义切割 → 向量化 → 存 Milvus + MySQL
 *
 * @author 屈轩
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq.listener", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "knowledge-topic",
        consumerGroup = "knowledge-group",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING,
        maxReconsumeTimes = 3
)
public class KnowledgeConsumer implements RocketMQListener<String> {

    private static final String KB_COLLECTION = "kb_chunk";
    private static final int MAX_CONTENT_LENGTH = 3500;

    @Resource
    private KnowledgeDocMapper knowledgeDocMapper;

    @Resource
    private PdfService pdfService;

    @Resource(name = "embeddingModel")
    private DashScopeEmbeddingModel embeddingModel;

    @Resource
    @Lazy
    private MilvusClientV2 milvusClient;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource(name = "summaryModel")
    private ChatModel chatModel;

    @Resource
    private ElasticAiSearchService elasticAiSearchService;

    @Override
    public void onMessage(String message) {
        log.info("[知识库] 收到消息: {}", message);
        JSONObject json = JSONUtil.parseObj(message);
        Long docId = json.getLong("docId");
        String url = json.getStr("url");
        String fileType = json.getStr("fileType");
        String fileName = json.getStr("fileName");

        updateStatus(docId, 1, null);
        try {
            byte[] fileBytes = downloadFromUrl(url);
            String markdown = convertToMarkdown(fileBytes, fileType);

            // 保存 markdown 原文到 MySQL
            KnowledgeDoc update = new KnowledgeDoc();
            update.setId(docId);
            update.setMarkdownContent(markdown);
            knowledgeDocMapper.updateById(update);

            // 大模型语义切割
            List<Chunk> chunks = splitByLlm(markdown, fileName);

            // 向量化并存储
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                storeChunk(chunk, fileName, i);
            }

            updateStatus(docId, 2, null);
            log.info("[知识库] 文档处理完成, docId={}, chunks={}", docId, chunks.size());
        } catch (Exception e) {
            log.error("[知识库] 文档处理失败, docId={}", docId, e);
            updateStatus(docId, 3, e.getMessage());
        }
    }

    private byte[] downloadFromUrl(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        return resp.body().readAllBytes();
    }

    private String convertToMarkdown(byte[] fileBytes, String fileType) throws Exception {
        if ("application/pdf".equals(fileType)) {
            return pdfService.ocrPdfBytes(fileBytes);
        }
        throw new IllegalArgumentException("暂不支持的文件类型: " + fileType);
    }

    /**
     * 使用大模型按语义切割 markdown，返回 chunk 列表
     */
    private List<Chunk> splitByLlm(String markdown, String fileName) {
        String promptText = "请将以下文档内容按语义分块，要求：\n"
                + "1. 每块内容完整，语义连贯，保留原文\n"
                + "2. 每块长度在200~500字之间，不要截断句子\n"
                + "3. 相关内容放在同一块\n"
                + "4. 为每块起一个简短标题\n"
                + "5. 仅返回 JSON 数组，格式：[{\"title\":\"标题\",\"content\":\"内容\"}, ...]\n"
                + "6. 不要有任何其他文字\n\n"
                + "文档：\n" + truncate(markdown, 12000);

        try {
            String response = chatModel.call(new Prompt(promptText))
                    .getResult().getOutput().getText();
            String jsonStr = extractJsonArray(response);
            JSONArray arr = JSONUtil.parseArray(jsonStr);
            return arr.stream()
                    .map(o -> {
                        JSONObject obj = (JSONObject) o;
                        return new Chunk(obj.getStr("title", fileName), obj.getStr("content", ""));
                    })
                    .filter(c -> !c.content().isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("[知识库] 大模型切割失败，降级为简单切割: {}", e.getMessage());
            return simpleChunk(markdown, fileName);
        }
    }

    /**
     * 提取响应中的 JSON 数组（可能带有 markdown 代码块）
     */
    private String extractJsonArray(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    /**
     * 降级：按固定字符数切割
     */
    private List<Chunk> simpleChunk(String text, String fileName) {
        int chunkSize = 400;
        java.util.List<Chunk> list = new java.util.ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(i + chunkSize, text.length());
            String part = text.substring(i, end).trim();
            if (!part.isBlank()) {
                list.add(new Chunk(fileName, part));
            }
            i += chunkSize - 50;
        }
        return list;
    }

    /**
     * 计算 embedding 并写入 Milvus kb_chunk 和 MySQL ai_kb_chunk
     */
    private void storeChunk(Chunk chunk, String source, int index) {
        String content = truncate(chunk.content(), MAX_CONTENT_LENGTH);
        String title = truncate(chunk.title(), 250);

        EmbeddingResponse embResp = embeddingModel.call(
                new EmbeddingRequest(List.of(content), null));
        float[] vector = embResp.getResults().get(0).getOutput();

        // 写入 MySQL，获取自增 chunk_id
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ai_kb_chunk (source, category, title, content, publish_time, active) "
                            + "VALUES (?, ?, ?, ?, NOW(), 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, source);
            ps.setString(2, "upload");
            ps.setString(3, title);
            ps.setString(4, content);
            return ps;
        }, keyHolder);

        long chunkId = keyHolder.getKey().longValue();

        // 写入 Milvus
        JsonObject row = new JsonObject();
        row.addProperty("chunk_id", chunkId);
        row.addProperty("category", "upload");
        row.addProperty("source", truncate(source, 250));
        row.addProperty("title", title);
        row.addProperty("content", content);
        long publishTimeMs = Instant.now().toEpochMilli();
        row.addProperty("publish_time", publishTimeMs);

        JsonArray vectorArray = new JsonArray();
        for (float v : vector) {
            vectorArray.add(v);
        }
        row.add("content_vector", vectorArray);

        milvusClient.insert(InsertReq.builder()
                .databaseName("sell")
                .collectionName(KB_COLLECTION)
                .data(List.of(row))
                .build());

        elasticAiSearchService.indexKnowledgeChunk(
                chunkId, "upload", source, title, content, publishTimeMs);
    }

    private void updateStatus(Long docId, int status, String errorMsg) {
        KnowledgeDoc update = new KnowledgeDoc();
        update.setId(docId);
        update.setStatus(status);
        update.setErrorMsg(errorMsg);
        knowledgeDocMapper.updateById(update);
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    private record Chunk(String title, String content) {}
}
