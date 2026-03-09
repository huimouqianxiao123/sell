package com.example.sell.ai.tool;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;

/**
 * 混合检索工具
 * 结合向量相似度搜索（Milvus）和关键词搜索（MySQL），
 * 从历史对话中检索与用户问题相关的内容，供 Agent 参考回答。
 *
 * 由 ReactAgent 在需要时自动调用。
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class HybridSearchTool implements Function<HybridSearchRequest, String> {

    private static final String COLLECTION_NAME = "chat_message";

    /** 结果内容截断长度 */
    private static final int MAX_CONTENT_DISPLAY = 500;

    @Resource
    private DashScopeEmbeddingModel embeddingModel;

    @Resource
    private MilvusClientV2 milvusClient;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public String apply(HybridSearchRequest request) {
        String query = request.getQuery();
        if (!StringUtils.hasText(query)) {
            return "查询文本不能为空。";
        }

        int limit = (request.getLimit() != null && request.getLimit() > 0)
                ? Math.min(request.getLimit(), 10) : 5;

        log.info("[混合检索] ========== 混合检索工具被调用 ==========");
        log.info("[混合检索] 查询: {}, limit: {}", query, limit);

        List<Map<String, Object>> allResults = new ArrayList<>();
        Set<String> seenContent = new HashSet<>();

        // ========== 第一路：向量相似度搜索（Milvus） ==========
        vectorSearch(query, limit, allResults, seenContent);

        // ========== 第二路：关键词搜索（MySQL） ==========
        keywordSearch(query, limit, allResults, seenContent);

        if (allResults.isEmpty()) {
            log.info("[混合检索] 未找到相关内容");
            return "未找到与查询相关的历史对话记录。";
        }

        String resultJson = JSONUtil.toJsonStr(Map.of("results", allResults, "total", allResults.size()));
        log.info("[混合检索] 返回 {} 条结果", allResults.size());
        log.info("[混合检索] ========== 混合检索工具执行完毕 ==========");
        return resultJson;
    }

    /**
     * 向量相似度搜索：通过 embedding 在 Milvus 中检索语义相似的历史对话
     */
    private void vectorSearch(String query, int limit,
                              List<Map<String, Object>> results, Set<String> seenContent) {
        try {
            // 计算查询文本的 embedding 向量
            EmbeddingResponse response = embeddingModel.call(
                    new EmbeddingRequest(List.of(query), null));
            float[] vector = response.getResult().getOutput();

            SearchReq searchReq = SearchReq.builder()
                    .databaseName("sell")
                    .collectionName(COLLECTION_NAME)
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .topK(limit)
                    .outputFields(List.of("session_id", "role", "content", "create_time"))
                    .build();

            SearchResp searchResp = milvusClient.search(searchReq);
            List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();

            if (!searchResults.isEmpty()) {
                for (SearchResp.SearchResult result : searchResults.get(0)) {
                    Map<String, Object> entity = result.getEntity();
                    String content = String.valueOf(entity.get("content"));

                    // 去重：相同内容只保留一条
                    if (seenContent.add(content)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("source", "semantic");
                        item.put("score", String.format("%.4f", result.getScore()));
                        item.put("sessionId", entity.get("session_id"));
                        item.put("role", entity.get("role"));
                        item.put("content", truncate(content));
                        results.add(item);
                    }
                }
            }

            log.info("[混合检索] 向量搜索返回 {} 条结果", results.size());
        } catch (Exception e) {
            log.error("[混合检索] 向量搜索失败", e);
        }
    }

    /**
     * 关键词搜索：通过 SQL LIKE 在 MySQL 中检索包含关键词的历史对话
     */
    private void keywordSearch(String query, int limit,
                               List<Map<String, Object>> results, Set<String> seenContent) {
        try {
            String sql = "SELECT session_id, role, content, create_time FROM ai_chat_message "
                    + "WHERE content LIKE ? AND event_type = 'message' "
                    + "ORDER BY create_time DESC LIMIT ?";

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    sql, "%" + query + "%", limit);

            int added = 0;
            for (Map<String, Object> row : rows) {
                String content = String.valueOf(row.get("content"));

                if (seenContent.add(content)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("source", "keyword");
                    item.put("score", "N/A");
                    item.put("sessionId", row.get("session_id"));
                    item.put("role", row.get("role"));
                    item.put("content", truncate(content));
                    results.add(item);
                    added++;
                }
            }

            log.info("[混合检索] 关键词搜索追加 {} 条去重后结果", added);
        } catch (Exception e) {
            log.error("[混合检索] 关键词搜索失败", e);
        }
    }

    /**
     * 截断过长内容
     */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > MAX_CONTENT_DISPLAY
                ? content.substring(0, MAX_CONTENT_DISPLAY) + "..."
                : content;
    }
}