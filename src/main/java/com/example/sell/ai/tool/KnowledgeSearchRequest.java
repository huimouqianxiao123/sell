package com.example.sell.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 主知识库检索工具入参
 * @author 屈轩
 */
@Data
public class KnowledgeSearchRequest {

    /** 检索查询文本（必填） */
    @JsonProperty("query")
    private String query;

    /** 返回数量上限，默认 5，最大 8 */
    @JsonProperty("limit")
    private Integer limit;
}
