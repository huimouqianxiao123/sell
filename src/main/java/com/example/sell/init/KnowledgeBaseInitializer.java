package com.example.sell.init;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.example.sell.ai.search.ElasticAiSearchService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 主知识库初始化：
 * - 读取 classpath:ai/kb/*.md
 * - 切片入 MySQL(ai_kb_chunk)
 * - 生成 embedding 入 Milvus(kb_chunk)
 * @author 屈轩
 */
@Slf4j
@Component
@DependsOn("milvusInitializer")
public class KnowledgeBaseInitializer {

    private static final String KB_TABLE = "ai_kb_chunk";
    private static final String KB_COLLECTION = "kb_chunk";
    private static final int CHUNK_SIZE = 380;
    private static final int CHUNK_OVERLAP = 80;
    private static final int MAX_CONTENT_LENGTH = 3500;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource(name = "embeddingModel")
    private DashScopeEmbeddingModel embeddingModel;

    @Lazy
    @Resource
    private MilvusClientV2 milvusClient;

    @Resource
    private ElasticAiSearchService elasticAiSearchService;

    @Value("${ai.kb.auto-init:true}")
    private boolean autoInit;

    @Value("${ai.kb.rebuild-on-startup:false}")
    private boolean rebuildOnStartup;

    @PostConstruct
    public void init() {
        if (!autoInit) {
            log.info("[知识库初始化] 已关闭（ai.kb.auto-init=false）");
            return;
        }
        try {
            ensureSchema();
            if (rebuildOnStartup) {
                jdbcTemplate.update("DELETE FROM " + KB_TABLE);
                log.warn("[知识库初始化] 已清空旧知识库数据（rebuild-on-startup=true）");
            }

            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + KB_TABLE, Long.class);
            if (count != null && count > 0 && !rebuildOnStartup) {
                log.info("[知识库初始化] 检测到已有 {} 条分片，跳过初始化", count);
                return;
            }

            List<KnowledgeDoc> docs = loadDocs();
            if (docs.isEmpty()) {
                log.warn("[知识库初始化] 未发现文档（classpath:ai/kb/*.md）");
                return;
            }

            int totalChunks = 0;
            for (KnowledgeDoc doc : docs) {
                List<String> chunks = splitIntoChunks(doc.content());
                for (String chunk : chunks) {
                    Long chunkId = insertChunk(doc, chunk);
                    if (chunkId != null) {
                        insertChunkVector(chunkId, doc, chunk);
                        totalChunks++;
                    }
                }
            }
            log.info("[知识库初始化] 完成，文档数={}，分片数={}", docs.size(), totalChunks);
        } catch (Exception e) {
            log.error("[知识库初始化] 失败", e);
        }
    }

    private void ensureSchema() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS ai_kb_chunk (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    category VARCHAR(64) NOT NULL COMMENT '知识类别(product/activity/after_sale)',
                    source VARCHAR(255) NOT NULL COMMENT '来源标识（文件名或外部来源）',
                    title VARCHAR(255) NULL COMMENT '标题',
                    content TEXT NOT NULL COMMENT '分片内容',
                    publish_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间/生效时间',
                    active TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
                    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_category_time (category, publish_time),
                    INDEX idx_active_time (active, publish_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI主知识库分片表'
                """;
        jdbcTemplate.execute(ddl);
    }

    private List<KnowledgeDoc> loadDocs() {
        List<KnowledgeDoc> docs = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            org.springframework.core.io.Resource[] resources = resolver.getResources("classpath:ai/kb/*.md");
            for (org.springframework.core.io.Resource resource : resources) {
                String filename = resource.getFilename();
                if (!StringUtils.hasText(filename)) {
                    continue;
                }
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                String category = parseCategory(filename);
                String title = parseTitle(content, filename);
                docs.add(new KnowledgeDoc(category, filename, title, content.trim()));
            }
        } catch (Exception e) {
            log.warn("[知识库初始化] 加载文档失败: {}", e.getMessage());
        }
        return docs;
    }

    private List<String> splitIntoChunks(String content) {
        List<String> chunks = new ArrayList<>();
        if (!StringUtils.hasText(content)) {
            return chunks;
        }
        String normalized = content.replace("\r\n", "\n").trim();
        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            String chunk = normalized.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
        }
        return chunks;
    }

    private Long insertChunk(KnowledgeDoc doc, String chunk) {
        String sql = "INSERT INTO " + KB_TABLE
                + " (category, source, title, content, publish_time, active) VALUES (?, ?, ?, ?, ?, 1)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, doc.category());
                ps.setString(2, doc.source());
                ps.setString(3, doc.title());
                ps.setString(4, chunk);
                ps.setTimestamp(5, java.sql.Timestamp.from(Instant.now()));
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception e) {
            log.warn("[知识库初始化] 插入分片失败, source={}: {}", doc.source(), e.getMessage());
            return null;
        }
    }

    private void insertChunkVector(Long chunkId, KnowledgeDoc doc, String chunk) {
        try {
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(List.of(chunk), null));
            float[] vector = response.getResult().getOutput();

            JsonObject row = new JsonObject();
            row.addProperty("chunk_id", chunkId);
            row.addProperty("category", doc.category());
            row.addProperty("source", doc.source());
            row.addProperty("title", doc.title());
            String safeContent = chunk.length() > MAX_CONTENT_LENGTH
                    ? chunk.substring(0, MAX_CONTENT_LENGTH)
                    : chunk;
            row.addProperty("content", safeContent);
            long publishTimeMs = Instant.now().toEpochMilli();
            row.addProperty("publish_time", publishTimeMs);

            JsonArray vec = new JsonArray();
            for (float v : vector) {
                vec.add(v);
            }
            row.add("content_vector", vec);

            milvusClient.insert(InsertReq.builder()
                    .databaseName("sell")
                    .collectionName(KB_COLLECTION)
                    .data(List.of(row))
                    .build());

            elasticAiSearchService.indexKnowledgeChunk(
                    chunkId, doc.category(), doc.source(), doc.title(), safeContent, publishTimeMs);
        } catch (Exception e) {
            log.warn("[知识库初始化] 向量写入失败, chunkId={}, source={}: {}",
                    chunkId, doc.source(), e.getMessage());
        }
    }

    private String parseCategory(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("product")) {
            return "product";
        }
        if (lower.contains("activity")) {
            return "activity";
        }
        if (lower.contains("after") || lower.contains("sale")) {
            return "after_sale";
        }
        return "general";
    }

    private String parseTitle(String content, String fallback) {
        for (String line : content.split("\n")) {
            if (line.startsWith("#")) {
                return line.replace("#", "").trim();
            }
        }
        return fallback;
    }

    private record KnowledgeDoc(String category, String source, String title, String content) {
    }
}
