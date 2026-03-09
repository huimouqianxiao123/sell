package com.example.sell.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 混合检索工具的入参 DTO
 * 字段均为可选，AI 根据用户意图按需填充
 *
 * @author 屈轩
 */
@Data
public class HybridSearchRequest {

    /** 搜索查询文本（必填） */
    @JsonProperty("query")
    private String query;

    /** 返回数量上限，默认 5，最大 10 */
    @JsonProperty("limit")
    private Integer limit;
}