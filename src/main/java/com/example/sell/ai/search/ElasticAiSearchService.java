package com.example.sell.ai.search;

import com.example.sell.entity.AiChatMessage;
import com.example.sell.entity.Product;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 关键词检索与索引同步服务。
 */
@Slf4j
@Service
public class ElasticAiSearchService {

    private static final String DOC_TYPE_KNOWLEDGE = "knowledge";
    private static final String DOC_TYPE_PRODUCT = "product";
    private static final String DOC_TYPE_MEMORY = "memory";

    @Resource
    private ElasticsearchOperations elasticsearchOperations;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${ai.elasticsearch.enabled:true}")
    private boolean enabled;

    @PostConstruct
    public void initIndex() {
        if (!enabled) {
            log.info("[ES检索] 已关闭（ai.elasticsearch.enabled=false）");
            return;
        }
        try {
            IndexOperations indexOps = elasticsearchOperations.indexOps(ElasticAiSearchDocument.class);
            if (!indexOps.exists()) {
                indexOps.create();
                indexOps.putMapping(indexOps.createMapping());
                log.info("[ES检索] 索引 sell_ai_search 创建完成");
            }
        } catch (Exception e) {
            log.warn("[ES检索] 初始化索引失败，将在检索时自动降级: {}", e.getMessage());
        }
    }

    public void indexKnowledgeChunk(Long chunkId, String category, String source,
                                    String title, String content, Long publishTimeMs) {
        if (chunkId == null || !StringUtils.hasText(content)) {
            return;
        }
        ElasticAiSearchDocument document = ElasticAiSearchDocument.builder()
                .id(DOC_TYPE_KNOWLEDGE + ":" + chunkId)
                .docType(DOC_TYPE_KNOWLEDGE)
                .refId(String.valueOf(chunkId))
                .category(category)
                .source(source)
                .title(title)
                .content(content)
                .searchText(join(title, source, category, content))
                .active(true)
                .timestampMs(publishTimeMs != null ? publishTimeMs : System.currentTimeMillis())
                .build();
        saveQuietly(document);
    }

    public void indexProduct(Product product) {
        if (product == null || product.getId() == null) {
            return;
        }
        String title = product.getName();
        String content = product.getDescription();
        ElasticAiSearchDocument document = ElasticAiSearchDocument.builder()
                .id(DOC_TYPE_PRODUCT + ":" + product.getId())
                .docType(DOC_TYPE_PRODUCT)
                .refId(String.valueOf(product.getId()))
                .category("product")
                .source("product")
                .title(title)
                .content(content)
                .searchText(join(title, content))
                .price(product.getPrice() == null ? null : product.getPrice().doubleValue())
                .stock(product.getStock())
                .productStatus(product.getStatus())
                .active(product.getStatus() == null || product.getStatus() == 1)
                .timestampMs(toEpochMs(product.getUpdateTime()))
                .build();
        saveQuietly(document);
    }

    public void indexMemoryMessage(AiChatMessage message) {
        if (message == null || message.getId() == null || !StringUtils.hasText(message.getContent())) {
            return;
        }
        indexMemoryText(
                String.valueOf(message.getId()),
                message.getUserId(),
                message.getSessionId(),
                message.getRole(),
                message.getEventType(),
                message.getContent(),
                toEpochMs(message.getCreateTime())
        );
    }

    public void indexMemoryText(String refId, String userId, String sessionId, String role,
                                String eventType, String content, Long timestampMs) {
        if (!StringUtils.hasText(refId) || !StringUtils.hasText(sessionId) || !StringUtils.hasText(content)) {
            return;
        }
        ElasticAiSearchDocument document = ElasticAiSearchDocument.builder()
                .id(DOC_TYPE_MEMORY + ":" + refId)
                .docType(DOC_TYPE_MEMORY)
                .refId(refId)
                .userId(userId)
                .sessionId(sessionId)
                .role(role)
                .eventType(eventType)
                .category("memory")
                .source("ai_chat_message")
                .title("会话记忆(" + safe(role) + ")")
                .content(content)
                .searchText(join(role, eventType, content))
                .active(true)
                .timestampMs(timestampMs != null ? timestampMs : System.currentTimeMillis())
                .build();
        saveQuietly(document);
    }

    public void deleteProduct(Long productId) {
        if (!enabled || productId == null) {
            return;
        }
        try {
            elasticsearchOperations.delete(DOC_TYPE_PRODUCT + ":" + productId, ElasticAiSearchDocument.class);
        } catch (Exception e) {
            log.warn("[ES检索] 删除商品索引失败, productId={}: {}", productId, e.getMessage());
        }
    }

    public List<AiSearchCandidate> searchKnowledge(String query, int limit) {
        return searchByDocType(query, DOC_TYPE_KNOWLEDGE, limit).stream()
                .filter(doc -> Boolean.TRUE.equals(doc.document().getActive()))
                .map(doc -> toCandidate(doc, DOC_TYPE_KNOWLEDGE))
                .toList();
    }

    public List<AiSearchCandidate> searchProducts(String query, int limit) {
        return searchByDocType(query, DOC_TYPE_PRODUCT, limit).stream()
                .filter(doc -> doc.document().getProductStatus() == null || doc.document().getProductStatus() == 1)
                .map(doc -> toCandidate(doc, DOC_TYPE_PRODUCT))
                .toList();
    }

    public List<AiSearchCandidate> searchMemory(String query, String userId, String sessionId,
                                                Long windowStartMs, int limit) {
        if (!StringUtils.hasText(query) || !StringUtils.hasText(sessionId)) {
            return List.of();
        }
        return searchMemoryDocuments(query, userId, sessionId, Math.max(limit * 3, limit)).stream()
                .filter(doc -> windowStartMs == null
                        || doc.document().getTimestampMs() == null
                        || doc.document().getTimestampMs() >= windowStartMs)
                .limit(limit)
                .map(doc -> toCandidate(doc, DOC_TYPE_MEMORY))
                .toList();
    }

    public Map<String, Integer> rebuildFromMysql() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("knowledge", rebuildKnowledgeFromMysql());
        result.put("product", rebuildProductsFromMysql());
        result.put("memory", rebuildMemoryFromMysql());
        return result;
    }

    private int rebuildKnowledgeFromMysql() {
        try {
            String sql = "SELECT id, category, source, title, content, publish_time FROM ai_kb_chunk WHERE active = 1";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> row : rows) {
                indexKnowledgeChunk(
                        longValue(row.get("id")),
                        stringValue(row.get("category")),
                        stringValue(row.get("source")),
                        stringValue(row.get("title")),
                        stringValue(row.get("content")),
                        epochMs(row.get("publish_time"))
                );
            }
            return rows.size();
        } catch (Exception e) {
            log.warn("[ES检索] 重建知识库索引失败: {}", e.getMessage());
            return 0;
        }
    }

    private int rebuildProductsFromMysql() {
        try {
            String sql = "SELECT id, name, price, stock, description, image, status, update_time FROM product";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> row : rows) {
                Product product = Product.builder()
                        .id(longValue(row.get("id")))
                        .name(stringValue(row.get("name")))
                        .price(row.get("price") == null ? null : new java.math.BigDecimal(String.valueOf(row.get("price"))))
                        .stock(integerValue(row.get("stock")))
                        .description(stringValue(row.get("description")))
                        .image(stringValue(row.get("image")))
                        .status(integerValue(row.get("status")))
                        .updateTime(localDateTime(row.get("update_time")))
                        .build();
                indexProduct(product);
            }
            return rows.size();
        } catch (Exception e) {
            log.warn("[ES检索] 重建商品索引失败: {}", e.getMessage());
            return 0;
        }
    }

    private int rebuildMemoryFromMysql() {
        try {
            String sql = "SELECT id, user_id, session_id, role, event_type, content, create_time "
                    + "FROM ai_chat_message WHERE event_type IN ('message', 'summary')";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> row : rows) {
                AiChatMessage message = AiChatMessage.builder()
                        .id(longValue(row.get("id")))
                        .userId(stringValue(row.get("user_id")))
                        .sessionId(stringValue(row.get("session_id")))
                        .role(stringValue(row.get("role")))
                        .eventType(stringValue(row.get("event_type")))
                        .content(stringValue(row.get("content")))
                        .createTime(localDateTime(row.get("create_time")))
                        .build();
                indexMemoryMessage(message);
            }
            return rows.size();
        } catch (Exception e) {
            log.warn("[ES检索] 重建记忆索引失败: {}", e.getMessage());
            return 0;
        }
    }

    private List<ScoredDocument> searchByDocType(String query, String docType, int limit) {
        if (!enabled || !StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b
                            .must(m -> m.multiMatch(mm -> mm
                                    .query(query)
                                    .fields("title^3", "content^2", "search_text", "source", "category")))
                            .filter(f -> f.term(t -> t.field("doc_type").value(docType)))))
                    .withPageable(PageRequest.of(0, safeLimit(limit)))
                    .build();
            SearchHits<ElasticAiSearchDocument> hits =
                    elasticsearchOperations.search(nativeQuery, ElasticAiSearchDocument.class);
            return hits.stream().map(this::toScoredDocument).toList();
        } catch (Exception e) {
            log.warn("[ES检索] 关键词检索失败, docType={}, query={}: {}", docType, query, e.getMessage());
            return List.of();
        }
    }

    private List<ScoredDocument> searchMemoryDocuments(String query, String userId, String sessionId, int limit) {
        if (!enabled) {
            return List.of();
        }
        try {
            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        b.must(m -> m.multiMatch(mm -> mm
                                .query(query)
                                .fields("content^2", "search_text", "title")));
                        b.filter(f -> f.term(t -> t.field("doc_type").value(DOC_TYPE_MEMORY)));
                        b.filter(f -> f.term(t -> t.field("session_id").value(sessionId)));
                        if (StringUtils.hasText(userId)) {
                            b.filter(f -> f.term(t -> t.field("user_id").value(userId)));
                        }
                        return b;
                    }))
                    .withPageable(PageRequest.of(0, safeLimit(limit)))
                    .build();
            SearchHits<ElasticAiSearchDocument> hits =
                    elasticsearchOperations.search(nativeQuery, ElasticAiSearchDocument.class);
            return hits.stream().map(this::toScoredDocument).toList();
        } catch (Exception e) {
            log.warn("[ES检索] 记忆关键词检索失败, sessionId={}, query={}: {}",
                    sessionId, query, e.getMessage());
            return List.of();
        }
    }

    private AiSearchCandidate toCandidate(ScoredDocument scoredDocument, String sourceType) {
        ElasticAiSearchDocument doc = scoredDocument.document();
        return AiSearchCandidate.builder()
                .sourceType(sourceType)
                .source(doc.getSource())
                .title(doc.getTitle())
                .content(doc.getContent())
                .timestampMs(doc.getTimestampMs())
                .keywordScore(scoredDocument.score())
                .build();
    }

    private ScoredDocument toScoredDocument(SearchHit<ElasticAiSearchDocument> hit) {
        return new ScoredDocument(hit.getContent(), hit.getScore());
    }

    private void saveQuietly(ElasticAiSearchDocument document) {
        if (!enabled || document == null) {
            return;
        }
        try {
            elasticsearchOperations.save(document);
        } catch (Exception e) {
            log.warn("[ES检索] 写入索引失败, id={}: {}", document.getId(), e.getMessage());
        }
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return 10;
        }
        return Math.min(Math.max(limit, 1), 50);
    }

    private String join(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                builder.append(value.trim()).append(' ');
            }
        }
        return builder.toString().trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Long epochMs(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.getTime();
        }
        if (value instanceof java.util.Date d) {
            return d.getTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return toEpochMs(ldt);
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private Long toEpochMs(LocalDateTime time) {
        if (time == null) {
            return System.currentTimeMillis();
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDateTime localDateTime(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        }
        if (value instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(d.getTime()), ZoneId.systemDefault());
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        return null;
    }

    private record ScoredDocument(ElasticAiSearchDocument document, double score) {
    }
}
