package com.example.sell.ai.tool;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.example.sell.ai.search.AiSearchCandidate;
import com.example.sell.ai.search.ElasticAiSearchService;
import com.example.sell.ai.search.RagFusionService;
import com.example.sell.common.AiRequestContextHolder;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 主知识库检索工具：
 * - 主语料：商品/活动/售后文档（Milvus 向量召回 + ES 关键词召回）
 * - 辅助记忆：当前用户会话历史（ES 关键词召回，按 user/session/time 强约束）
 * - 重排序：语义分 + ES BM25 分 + 关键词覆盖率 + 时效分（memory）
 * - 输出：携带引用片段（来源/时间/置信度）
 * @author 屈轩
 */
@Slf4j
@Component
public class KnowledgeSearchTool implements Function<KnowledgeSearchRequest, String> {

    /** Milvus 知识库集合名称 */
    private static final String KB_COLLECTION = "kb_chunk";
    /** 默认返回的最大结果数量 */
    private static final int MAX_LIMIT = 8;
    /** 向量召回候选倍率（实际召回数量 = limit * 此值，为重排留出空间） */
    private static final int VECTOR_CANDIDATE_MULTIPLIER = 4;
    /** 引用片段截断最大长度 */
    private static final int MAX_SNIPPET_LENGTH = 240;
    /** 时间格式化器 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Resource
    private DashScopeEmbeddingModel embeddingModel;

    @Lazy
    @Resource
    private MilvusClientV2 milvusClient;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ElasticAiSearchService elasticAiSearchService;

    @Resource
    private RagFusionService ragFusionService;

    @Override
    public String apply(KnowledgeSearchRequest request) {
        // 参数校验
        String query = request != null ? request.getQuery() : null;
        if (!StringUtils.hasText(query)) {
            return "检索关键词不能为空。";
        }

        // 计算返回条数上限（默认 5，最大不超过 MAX_LIMIT）
        int limit = (request.getLimit() != null && request.getLimit() > 0)
                ? Math.min(request.getLimit(), MAX_LIMIT)
                : 5;

        List<AiSearchCandidate> candidates = new ArrayList<>();

        // 第一阶段：主知识库召回（Milvus 语义 + ES 关键词）
        int candidateLimit = limit * VECTOR_CANDIDATE_MULTIPLIER;
        retrieveFromKnowledgeBase(query, candidateLimit, candidates);
        candidates.addAll(elasticAiSearchService.searchKnowledge(query, candidateLimit));
        candidates.addAll(elasticAiSearchService.searchProducts(query, limit));
        if (candidates.isEmpty()) {
            retrieveKnowledgeByKeyword(query, candidateLimit, candidates);
        }

        // 第二阶段：会话记忆辅助召回（ES 关键词，强约束：当前用户、当前会话、时间窗口内）
        retrieveFromAuxMemory(query, limit, candidates);

        if (candidates.isEmpty()) {
            return "未检索到相关知识，请尝试换个问法。";
        }

        // 第三阶段：多维融合重排序（Milvus 语义分 + ES BM25 + 关键词覆盖率 + 时效性）
        List<AiSearchCandidate> top = ragFusionService.mergeAndRerank(query, candidates, limit);

        // 将引用片段加入请求上下文，供后续 LLM 生成时引用
        AiRequestContextHolder.addCitations(top.stream()
                .map(this::toCitation)
                .toList());

        // 组装 JSON 响应
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("constraints", buildConstraints());
        response.put("snippets", top.stream().map(this::toSnippet).toList());
        response.put("total", top.size());
        return JSONUtil.toJsonStr(response);
    }

    /**
     * 主知识库向量召回
     * 使用 DashScope Embedding 将查询转为向量，在 Milvus 中执行 ANN 近似最近邻搜索
     */
    private void retrieveFromKnowledgeBase(String query, int topK,
                                           List<AiSearchCandidate> out) {
        try {
            // 将查询文本转为 embedding 向量
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(List.of(query), null));
            float[] vector = response.getResult().getOutput();

            // 构建 Milvus 搜索请求
            SearchReq searchReq = SearchReq.builder()
                    .databaseName("sell")
                    .collectionName(KB_COLLECTION)
                    .data(List.of(new FloatVec(vector)))
                    .topK(topK)
                    .outputFields(List.of("category", "source", "title", "content", "publish_time"))
                    .build();

            SearchResp searchResp = milvusClient.search(searchReq);
            List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();
            if (searchResults == null || searchResults.isEmpty()) {
                return;
            }
            // 遍历召回结果，组装 Candidate
            for (SearchResp.SearchResult item : searchResults.get(0)) {
                Map<String, Object> entity = item.getEntity();
                String content = stringValue(entity.get("content"));
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                out.add(AiSearchCandidate.builder()
                        .sourceType("knowledge")
                        .source(stringValue(entity.get("source")))
                        .title(stringValue(entity.get("title")))
                        .content(content)
                        .timestampMs(longValue(entity.get("publish_time")))
                        .semanticScore(item.getScore())
                        .build());
            }
        } catch (Exception e) {
            log.warn("[知识检索] Milvus 向量检索失败，继续使用 ES/SQL 关键词检索: {}", e.getMessage());
        }
    }

    /**
     * 关键词回退检索：当 ES 不可用且向量检索无结果时，使用 SQL LIKE 模糊匹配
     */
    private void retrieveKnowledgeByKeyword(String query, int limit,
                                            List<AiSearchCandidate> out) {
        try {
            String sql = "SELECT source, title, content, publish_time FROM ai_kb_chunk "
                    + "WHERE active = 1 AND content LIKE ? ORDER BY publish_time DESC LIMIT ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, "%" + query + "%", limit);
            for (Map<String, Object> row : rows) {
                String content = stringValue(row.get("content"));
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                out.add(AiSearchCandidate.builder()
                        .sourceType("knowledge")
                        .source(stringValue(row.get("source")))
                        .title(stringValue(row.get("title")))
                        .content(content)
                        .timestampMs(epochMs(row.get("publish_time")))
                        .keywordScore(1.0)
                        .build());
            }
        } catch (Exception e) {
            log.warn("[知识检索] 关键词回退失败: {}", e.getMessage());
        }
    }

    /**
     * 会话记忆辅助召回：从当前用户的聊天历史中检索相关内容
     * 强约束条件：session_id、时间窗口、可选的 user_id
     */
    private void retrieveFromAuxMemory(String query, int limit,
                                       List<AiSearchCandidate> out) {
        AiRequestContextHolder.AiRequestContext ctx = AiRequestContextHolder.get();
        // 没有会话上下文则跳过
        if (ctx == null || !StringUtils.hasText(ctx.sessionId())) {
            return;
        }

        List<AiSearchCandidate> esMemories = elasticAiSearchService.searchMemory(
                query,
                ctx.userId(),
                ctx.sessionId(),
                ctx.windowStart().toEpochMilli(),
                Math.max(limit * 2, 6)
        );
        if (!esMemories.isEmpty()) {
            out.addAll(esMemories);
            return;
        }

        try {
            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder(
                    "SELECT role, content, create_time FROM ai_chat_message "
                            + "WHERE event_type = 'message' AND session_id = ? "
                            + "AND create_time >= ? AND content LIKE ? ");
            params.add(ctx.sessionId());
            params.add(Timestamp.from(ctx.windowStart()));
            params.add("%" + query + "%");

            // 如果存在用户 ID，追加 user_id 过滤条件
            if (StringUtils.hasText(ctx.userId())) {
                sql.append("AND user_id = ? ");
                params.add(ctx.userId());
            }

            sql.append("ORDER BY create_time DESC LIMIT ?");
            params.add(Math.max(limit * 2, 6));   // 会话记忆多召回一些，后续靠重排序筛选

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            for (Map<String, Object> row : rows) {
                String content = stringValue(row.get("content"));
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                out.add(AiSearchCandidate.builder()
                        .sourceType("memory")
                        .source("ai_chat_message")
                        .title("会话记忆(" + stringValue(row.get("role")) + ")")
                        .content(content)
                        .timestampMs(epochMs(row.get("create_time")))
                        .keywordScore(0.5)
                        .build());
            }
        } catch (Exception e) {
            log.warn("[知识检索] 会话记忆检索失败: {}", e.getMessage());
        }
    }

    /**
     * 构建请求约束条件 Map，用于响应中返回检索上下文
     */
    private Map<String, Object> buildConstraints() {
        AiRequestContextHolder.AiRequestContext ctx = AiRequestContextHolder.get();
        Map<String, Object> constraints = new LinkedHashMap<>();
        if (ctx == null) {
            constraints.put("sessionFilter", "none");
            constraints.put("timeWindow", "none");
            return constraints;
        }
        constraints.put("userId", StringUtils.hasText(ctx.userId()) ? ctx.userId() : "anonymous");
        constraints.put("sessionId", StringUtils.hasText(ctx.sessionId()) ? ctx.sessionId() : "none");
        constraints.put("timeWindowStart", TIME_FORMATTER.format(ctx.windowStart()));
        constraints.put("requestTime", TIME_FORMATTER.format(ctx.requestTime()));
        return constraints;
    }

    /**
     * 将 Candidate 转为摘要 Map（用于 JSON 响应）
     */
    private Map<String, Object> toSnippet(AiSearchCandidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceType", c.getSourceType());
        m.put("source", c.getSource());
        m.put("title", c.getTitle());
        m.put("snippet", truncate(c.getContent()));
        m.put("time", c.getTimestampMs() == null || c.getTimestampMs() <= 0
                ? ""
                : TIME_FORMATTER.format(java.time.Instant.ofEpochMilli(c.getTimestampMs())));
        m.put("confidence", String.format("%.4f", c.getFinalScore()));
        return m;
    }

    /**
     * 将 Candidate 转为引用片段对象（供 LLM 上下文引用）
     */
    private AiRequestContextHolder.CitationSnippet toCitation(AiSearchCandidate c) {
        return new AiRequestContextHolder.CitationSnippet(
                c.getSourceType(),
                c.getSource(),
                c.getTitle(),
                truncate(c.getContent()),
                c.getTimestampMs(),
                c.getFinalScore()
        );
    }

    /** 截断文本到最大长度，超出部分追加 "..." */
    private String truncate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.length() <= MAX_SNIPPET_LENGTH
                ? text
                : text.substring(0, MAX_SNIPPET_LENGTH) + "...";
    }

    /** 安全地将对象转为字符串 */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 安全地将对象转为 Long */
    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number n) {
                return n.longValue();
            }
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将数据库时间类型（Timestamp / Date / 数值）统一转为毫秒时间戳
     */
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
        return longValue(value);
    }

}
