package com.example.sell.ai.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * ES 统一检索文档。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "sell_ai_search", createIndex = false)
public class ElasticAiSearchDocument {

    @Id
    private String id;

    @Field(name = "doc_type", type = FieldType.Keyword)
    private String docType;

    @Field(name = "ref_id", type = FieldType.Keyword)
    private String refId;

    @Field(name = "user_id", type = FieldType.Keyword)
    private String userId;

    @Field(name = "session_id", type = FieldType.Keyword)
    private String sessionId;

    @Field(type = FieldType.Keyword)
    private String role;

    @Field(name = "event_type", type = FieldType.Keyword)
    private String eventType;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String source;

    @Field(type = FieldType.Text)
    private String title;

    @Field(type = FieldType.Text)
    private String content;

    @Field(name = "search_text", type = FieldType.Text)
    private String searchText;

    @Field(type = FieldType.Double)
    private Double price;

    @Field(type = FieldType.Integer)
    private Integer stock;

    @Field(name = "product_status", type = FieldType.Integer)
    private Integer productStatus;

    @Field(type = FieldType.Boolean)
    private Boolean active;

    @Field(name = "timestamp_ms", type = FieldType.Long)
    private Long timestampMs;
}
