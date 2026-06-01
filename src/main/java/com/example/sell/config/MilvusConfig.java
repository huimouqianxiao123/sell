package com.example.sell.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * @author 屈轩
 */
@Configuration
public class MilvusConfig {

    @Bean
    public ConnectConfig connectConfig() {
        return ConnectConfig.builder()
                .uri("http://43.139.17.130:19530")
                .build();
    }

    @Lazy
    @Bean
    public MilvusClientV2 milvusClient(ConnectConfig connectConfig) {
        return new MilvusClientV2(connectConfig);
    }
}