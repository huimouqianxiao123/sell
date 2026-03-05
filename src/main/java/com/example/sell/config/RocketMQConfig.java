package com.example.sell.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ配置类
 * 提供RocketMQ模板和健康检查
 * @author 屈轩
 */
@Slf4j
@Configuration
public class RocketMQConfig {
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 初始化后检查RocketMQ连接状态
     */
    @PostConstruct
    public void init() {
        try {
            log.info("【RocketMQ】开始初始化，NameServer地址: {}", 
                rocketMQTemplate.getProducer().getNamesrvAddr());
            log.info("【RocketMQ】生产者组: {}", 
                rocketMQTemplate.getProducer().getProducerGroup());
            
            log.info("【RocketMQ】生产者已由Spring Boot自动启动，无需手动启动");
        } catch (Exception e) {
            log.error("【RocketMQ】初始化检查失败，异常信息: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取RocketMQ模板
     * @return RocketMQTemplate实例
     */
    public RocketMQTemplate getRocketMqTemplate() {
        return rocketMQTemplate;
    }
}
