package com.example.sell.ai.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Tavily 搜索工具
 * 调用 Tavily API 搜索互联网实时信息，供 Agent 回答时效性问题。
 *
 * @author 屈轩
 */
@Component
@RequiredArgsConstructor
public class TavilySearchTool implements Function<TavilySearchRequest, String> {

    private final RestClient restClient;

    @Value("${tavily.api-key}")
    private String apiKey;

    @Override
    public String apply(TavilySearchRequest request) {
        String query = request.getQuery();
        if (!StringUtils.hasText(query)) {
            return "搜索关键词不能为空。";
        }

        int maxResults = (request.getMaxResults() != null && request.getMaxResults() > 0)
                ? Math.min(request.getMaxResults(), 10) : 5;

        Map<String, Object> body = Map.of(
                "api_key", apiKey,
                "query", query,
                "max_results", maxResults,
                "include_answer", true
        );

        TavilyResponse response = restClient.post()
                .uri("/search")
                .body(body)
                .retrieve()
                .body(TavilyResponse.class);

        return formatResponse(response);
    }

    private String formatResponse(TavilyResponse response) {
        if (response == null) {
            return "搜索失败，未获取到任何结果";
        }

        StringBuilder sb = new StringBuilder();

        if (response.answer() != null && !response.answer().isBlank()) {
            sb.append("【直接答案】\n")
                    .append(response.answer())
                    .append("\n\n");
        }

        List<TavilyResponse.SearchResult> results = response.results();
        if (results == null || results.isEmpty()) {
            sb.append("未找到相关搜索结果");
            return sb.toString();
        }

        sb.append("【搜索结果】\n");
        for (int i = 0; i < results.size(); i++) {
            TavilyResponse.SearchResult r = results.get(i);
            sb.append(i + 1).append(". ")
                    .append(r.title()).append("\n")
                    .append("   链接: ").append(r.url()).append("\n")
                    .append("   摘要: ").append(r.content()).append("\n\n");
        }

        return sb.toString();
    }
}
