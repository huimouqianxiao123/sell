package com.example.sell.ai.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TavilyResponse(

        @JsonProperty("answer")
        String answer,

        @JsonProperty("results")
        List<SearchResult> results
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResult(
            @JsonProperty("title")   String title,
            @JsonProperty("url")     String url,
            @JsonProperty("content") String content,
            @JsonProperty("score")   double score
    ) {}
}