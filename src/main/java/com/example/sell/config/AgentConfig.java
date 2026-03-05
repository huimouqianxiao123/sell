package com.example.sell.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.example.sell.ai.tool.ProductQueryRequest;
import com.example.sell.ai.tool.ProductQueryTool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * AI 智能客服 Agent 配置
 * 拆分为两个 Agent：
 * 1. marketingAgent — 商品客服 Agent（含 ProductQueryTool + HITL + SummarizationHook）
 * 2. generalAgent  — 通用聊天 Agent（无工具、无 HITL）
 *
 * HITL 流程说明：
 * HITL Hook 会在工具调用前中断 Agent，返回 InterruptionMetadata。
 * 由于中断会导致 RedisSaver 保存不完整的消息链（AssistantMessage 含 tool_calls
 * 但缺少 ToolResponseMessage），恢复时不能复用原 threadId。
 * 完整恢复流程见 AgentNode / AiProductCustomerServiceImp。
 *
 * @author 屈轩
 */
@Slf4j
@Configuration
public class AgentConfig {

    @Resource(name = "summaryModel")
    private ChatModel summaryModel;

    @Resource(name = "bigModel")
    private ChatModel bigModel;

    @Resource
    private ProductQueryTool productQueryTool;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private SkillsAgentHook skillsAgentHook;

    /**
     * Redis 会话记忆（按 threadId 隔离）
     * 供两个 Agent 共享使用
     */
    @Bean
    public RedisSaver redisSaver() {
        return RedisSaver.builder()
                .redisson(redissonClient)
                .build();
    }

    /**
     * 商品客服 Agent（MARKETING 分类走此 Agent）
     * 具备：商品查询工具 + HITL 确认 + 上下文压缩 + 技能扩展
     */
    @Bean("marketingAgent")
    public ReactAgent marketingAgent(RedisSaver redisSaver) {
        // 加载知识文档作为系统提示
        String systemPrompt = loadSystemPrompt();

        // 商品查询工具：AI 可自主调用，执行安全的参数化 SQL 查询
        ToolCallback productQueryCallback = FunctionToolCallback
                .builder("queryProducts", productQueryTool)
                .description("查询商品信息。支持按名称关键词模糊搜索、价格范围过滤、"
                        + "是否只查在售商品等条件组合查询。"
                        + "参数说明：nameKeyword=名称关键词, minPrice=最低价格, "
                        + "maxPrice=最高价格, onSaleOnly=是否只查在售, limit=返回数量(最大20)")
                .inputType(ProductQueryRequest.class)
                .build();

        // HITL Hook：工具调用前提示用户确认
        HumanInTheLoopHook hitlHook = HumanInTheLoopHook.builder()
                .approvalOn("queryProducts", ToolConfig.builder()
                        .description("即将执行商品查询，请确认查询条件")
                        .build())
                .build();

        // 上下文压缩
        SummarizationHook summarizationHook = SummarizationHook.builder()
                .model(summaryModel)
                .maxTokensBeforeSummary(50) // 临时调小用于测试，验证通过后改回 10000
                .keepFirstUserMessage(true)
                .messagesToKeep(2)
                .summaryPrefix("以下是最近的对话：")
                .summaryPrompt("请将上下文压缩为以下内容：")
                .build();

        return ReactAgent.builder()
                .name("product_customer_service")
                .model(bigModel)
                .systemPrompt(systemPrompt)
                .tools(List.of(productQueryCallback))
                .hooks(List.of(hitlHook, summarizationHook, skillsAgentHook))
                .saver(redisSaver)
                .build();
    }

    /**
     * 通用聊天 Agent（NORMAL/SECURITY/OTHER 分类走此 Agent）
     * 无工具、无 HITL，纯对话
     */
    @Bean("generalAgent")
    public ReactAgent generalAgent(RedisSaver redisSaver) {
        // 上下文压缩（通用 Agent 也需要防止上下文爆炸）
        SummarizationHook summarizationHook = SummarizationHook.builder()
                .model(summaryModel)
                .maxTokensBeforeSummary(50) // 临时调小用于测试，验证通过后改回 10000
                .keepFirstUserMessage(true)
                .messagesToKeep(2)
                .summaryPrefix("以下是最近的对话：")
                .summaryPrompt("请将上下文压缩为以下内容：")
                .build();

        return ReactAgent.builder()
                .name("general_chat")
                .model(bigModel)
                .systemPrompt("你是一个友好的AI助手，可以回答各类问题。请使用中文回答。" +
                        "如果用户问到商品相关的问题，请引导他们明确表达需求，你会转交给专业的商品客服处理。")
                .tools(List.of())
                .hooks(List.of(summarizationHook))
                .saver(redisSaver)
                .build();
    }

    /**
     * 从 classpath 加载知识文档，作为商品客服 Agent 的系统提示
     */
    private String loadSystemPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("ai/product-knowledge.md");
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.info("AI 知识文档加载成功，长度: {} 字符", content.length());
            return content;
        } catch (IOException e) {
            log.warn("AI 知识文档加载失败，使用默认提示", e);
            return "你是一个电商平台的商品客服助手，帮助用户查询商品信息。请使用中文回答。";
        }
    }
}
