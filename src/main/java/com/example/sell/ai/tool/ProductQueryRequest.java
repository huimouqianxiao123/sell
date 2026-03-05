package com.example.sell.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * AI 商品查询工具的入参 DTO
 * 字段均为可选，AI 根据用户意图按需填充
 */
@Data
public class ProductQueryRequest {

    /** 商品名称关键词（模糊匹配） */
    @JsonProperty("nameKeyword")
    private String nameKeyword;

    /** 最低价格（元） */
    @JsonProperty("minPrice")
    private BigDecimal minPrice;

    /** 最高价格（元） */
    @JsonProperty("maxPrice")
    private BigDecimal maxPrice;

    /** 是否只查在售商品（status=1） */
    @JsonProperty("onSaleOnly")
    private Boolean onSaleOnly;

    /** 返回数量上限，默认 10，最大 20 */
    @JsonProperty("limit")
    private Integer limit;
}
