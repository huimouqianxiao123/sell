package com.example.sell.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Tavily 搜索工具的入参 DTO
 *
 * @author 屈轩
 */
@Data
public class TavilySearchRequest {

    /** 搜索关键词或问题（必填） */
    @JsonProperty("query")
    private String query;

    /** 最大结果数，默认 5，最大 10 */
    @JsonProperty("maxResults")
    private Integer maxResults;
}
