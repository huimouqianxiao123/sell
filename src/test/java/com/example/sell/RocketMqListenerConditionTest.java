package com.example.sell;

import com.example.sell.consumer.KnowledgeConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RocketMqListenerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("rocketmq.listener.enabled=false")
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldNotRegisterKnowledgeConsumerWhenRocketMqListenerDisabled() {
        contextRunner.run(context -> {
            assertFalse(context.getStartupFailure() != null);
            assertFalse(context.containsBean("knowledgeConsumer"));
        });
    }

    @Configuration
    @Import(KnowledgeConsumer.class)
    static class TestConfig {
    }
}
