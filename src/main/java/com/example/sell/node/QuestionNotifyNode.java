package com.example.sell.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.example.sell.ai.tool.QuestionStyle;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 问题分类节点：对用户输入的问题进行意图分类
 * 使用轻量级模型（qwen-turbo）进行快速分类
 *
 * 输入状态：StateKeys.QUESTION（用户问题）
 * 输出状态：StateKeys.QUESTION_STYLE（分类结果）
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class QuestionNotifyNode implements NodeActionWithConfig {

    @Resource
    private ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            你是一个问题分类助手，请将用户问题分类为以下类型之一：
            - NORMAL: 通用问题（如问候、闲聊、知识问答）
            - MARKETING: 营销相关问题（如商品查询、促销、优惠券、活动咨询、价格咨询、购物相关）
            - SECURITY: 安全问题（如账号安全、支付风险、隐私保护）
            - OTHER: 其他无法归类的问题
            请只返回分类标识（NORMAL/MARKETING/SECURITY/OTHER），不要返回其他内容。
            """;

    @Override
    public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
        // 1. 从 state 中获取用户问题
        String question = state.value(StateKeys.QUESTION, String.class)
                .orElseThrow(() -> new IllegalArgumentException("未找到问题内容: " + StateKeys.QUESTION));

        log.info("[问题分类] 开始分类, 问题: {}", question);

        // 2. 调用分类逻辑
        String style = classifyQuestion(question);

        log.info("[问题分类] 分类结果: {}", style);

        // 3. 返回分类结果到 state，供条件路由使用
        return Map.of(StateKeys.QUESTION_STYLE, style);
    }

    /**
     * 调用大模型进行问题分类
     */
    private String classifyQuestion(String question) {
        try {
            String result = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(question)
                    .call()
                    .content();

            // 校验返回值是否为合法枚举
            if (result != null) {
                String trimmed = result.trim().toUpperCase();
                try {
                    QuestionStyle.valueOf(trimmed);
                    return trimmed;
                } catch (IllegalArgumentException e) {
                    log.warn("[问题分类] 模型返回非法分类: {}, 降级为 OTHER", result);
                }
            }
            return QuestionStyle.OTHER.name();
        } catch (Exception e) {
            log.error("[问题分类] 分类失败，降级为 OTHER", e);
            return QuestionStyle.OTHER.name();
        }
    }
}
