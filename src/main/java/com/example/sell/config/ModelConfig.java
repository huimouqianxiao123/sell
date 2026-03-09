package com.example.sell.config;


import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;


import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author 屈轩
 */
@Configuration
public class ModelConfig {
    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Bean("summaryModel")
    public ChatModel summaryModel() {
        // 不传 baseUrl，使用 DashScopeApi 默认地址（https://dashscope.aliyuncs.com）
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model("qwen-turbo") // 轻量模型，用于对话摘要压缩
                .temperature(0.1)
                .build();
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(options)
                .build();
    }

    @Bean("bigModel")  // ✅ 修正拼写
    public ChatModel bigModel() {
        // 不传 baseUrl，使用 DashScopeApi 默认地址（https://dashscope.aliyuncs.com）
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model("qwen-plus") // 主对话模型，性价比均衡
                .temperature(0.7)
                .build();
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(options)
                .build();
    }

    @Bean("embeddingModel")
    public DashScopeEmbeddingModel embeddingModel() {
        // 不传 baseUrl，使用 DashScopeApi 默认地址（https://dashscope.aliyuncs.com）
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .withModel("text-embedding-v1")
                .build();
        MetadataMode metadataMode = MetadataMode.NONE;
        return new DashScopeEmbeddingModel(dashScopeApi,metadataMode,options);
    }



}