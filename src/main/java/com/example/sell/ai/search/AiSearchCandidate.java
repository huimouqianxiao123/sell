package com.example.sell.ai.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * RAG 检索候选结果，统一承载 Milvus 语义召回、ES 关键词召回和记忆召回。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSearchCandidate {

    /** knowledge/product/memory */
    private String sourceType;

    /** 来源标识，例如文件名、商品表、聊天消息表 */
    private String source;

    /** 展示标题 */
    private String title;

    /** 召回正文 */
    private String content;

    /** 毫秒时间戳 */
    private Long timestampMs;

    /** Milvus 语义相似度 */
    private double semanticScore;

    /** ES BM25 关键词分数 */
    private double keywordScore;

    /** 查询词覆盖率 */
    private double lexicalScore;

    /** 融合后的最终分数 */
    private double finalScore;

    public String dedupeKey() {
        if (StringUtils.hasText(content)) {
            return normalize(sourceType) + "|" + normalize(content);
        }
        return normalize(sourceType) + "|" + normalize(source) + "|" + normalize(title);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
