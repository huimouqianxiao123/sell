package com.example.sell.config;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient Bean 配置
 * 供 QuestionNotifyNode 等需要轻量级分类调用的组件使用
 *
 * @author 屈轩
 */
@Configuration
public class ChatClientConfig {

    @Resource(name = "summaryModel")
    private ChatModel summaryModel;

    /**
     * 使用轻量级模型（qwen-turbo）构建 ChatClient
     * 用于问题分类等简单任务，节省成本
     */
    @Bean
    public ChatClient chatClient() {
        return ChatClient.builder(summaryModel).build();
    }



}
