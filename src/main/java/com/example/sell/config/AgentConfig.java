package com.example.sell.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.example.sell.ai.tool.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    private KnowledgeSearchTool knowledgeSearchTool;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private SkillsAgentHook skillsAgentHook;
    @Resource
    private TavilySearchTool tavilySearchTool;

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

        // Tavily 搜索工具：搜索互联网实时信息
        ToolCallback searchCallback = FunctionToolCallback
                .builder("search", tavilySearchTool)
                .description("使用 Tavily 搜索引擎搜索互联网实时信息，适合回答时效性强的问题。"
                        + "参数说明：query=搜索关键词(必填), maxResults=最大结果数(默认5,最大10)")
                .inputType(TavilySearchRequest.class)
                .build();

        // 商品查询工具：AI 可自主调用，执行安全的参数化 SQL 查询
        ToolCallback productQueryCallback = FunctionToolCallback
                .builder("queryProducts", productQueryTool)
                .description("查询商品信息。支持按名称关键词模糊搜索、价格范围过滤、"
                        + "是否只查在售商品等条件组合查询。"
                        + "参数说明：nameKeyword=名称关键词, minPrice=最低价格, "
                        + "maxPrice=最高价格, onSaleOnly=是否只查在售, limit=返回数量(最大20)")
                .inputType(ProductQueryRequest.class)
                .build();


        // 主知识库检索工具：主语料（商品/活动/售后）+ 强约束会话记忆 + rerank
        ToolCallback knowledgeSearchCallback = FunctionToolCallback
                .builder("knowledgeSearch", knowledgeSearchTool)
                .description("检索主知识库并返回可引用片段。"
                        + "主语料来自商品/活动/售后文档；会话记忆仅作辅助，且会按 user/session/time 强约束过滤。"
                        + "返回内容包含来源、时间、置信度，可直接用于回答时引用。"
                        + "参数说明：query=检索文本(必填), limit=返回数量(默认5,最大8)")
                .inputType(KnowledgeSearchRequest.class)
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
                .maxTokensBeforeSummary(8000) // 临时调小用于测试，验证通过后改回 10000
                .keepFirstUserMessage(true)
                .messagesToKeep(10)
                .summaryPrefix("以下是最近的对话：")
                .summaryPrompt("请将上下文压缩为以下内容：")
                .build();

        return ReactAgent.builder()
                .name("product_customer_service")
                .model(bigModel)
                .systemPrompt(systemPrompt + buildCitationInstruction())
                .tools(List.of(productQueryCallback, knowledgeSearchCallback, searchCallback))
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
        ToolCallback searchCallback = FunctionToolCallback
                .builder("search", tavilySearchTool)
                .description("使用 Tavily 搜索引擎搜索互联网实时信息，适合回答时效性强的问题。"
                        + "参数说明：query=搜索关键词(必填), maxResults=最大结果数(默认5,最大10)")
                .inputType(TavilySearchRequest.class)
                .build();
        ToolCallback knowledgeSearchCallback = FunctionToolCallback
                .builder("knowledgeSearch", knowledgeSearchTool)
                .description("检索主知识库并返回可引用片段。"
                        + "主语料来自商品/活动/售后文档；会话记忆仅作辅助，且会按 user/session/time 强约束过滤。"
                        + "返回内容包含来源、时间、置信度，可直接用于回答时引用。"
                        + "参数说明：query=检索文本(必填), limit=返回数量(默认5,最大8)")
                .inputType(KnowledgeSearchRequest.class)
                .build();

        // 上下文压缩（通用 Agent 也需要防止上下文爆炸）
        SummarizationHook summarizationHook = SummarizationHook.builder()
                .model(summaryModel)
                .maxTokensBeforeSummary(8000) // 临时调小用于测试，验证通过后改回 10000
                .keepFirstUserMessage(true)
                .messagesToKeep(10)
                .summaryPrefix("以下是最近的对话：")
                .summaryPrompt("请将上下文压缩为以下内容：")
                .build();

        return ReactAgent.builder()
                .name("general_chat")
                .model(bigModel)
                .systemPrompt("你是一个友好的AI助手，可以回答各类问题。请使用中文回答。"
                        + "如果用户问到商品相关的问题，请优先使用 knowledgeSearch 检索主知识库，再给出答案。"
                        + buildCitationInstruction())
                .tools(List.of(knowledgeSearchCallback, searchCallback))
                .hooks(List.of(summarizationHook))
                .saver(redisSaver)
                .build();
    }

    /**
     * 从 classpath 加载知识文档，作为商品客服 Agent 的系统提示
     */
    private String loadSystemPrompt() {
        try {
            org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource("ai/product-knowledge.md");
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.info("AI 知识文档加载成功，长度: {} 字符", content.length());
            return content;
        } catch (IOException e) {
            log.warn("AI 知识文档加载失败，使用默认提示", e);
            return "你是一个电商平台的商品客服助手，帮助用户查询商品信息。请使用中文回答。";
        }
    }

    private String buildCitationInstruction() {
        return "\n\n【回答要求】\n"
                + "1. 涉及商品、活动、售后政策时，先调用 knowledgeSearch 再回答。\n"
                + "2. 回答末尾必须附“参考来源”列表，格式为：来源 / 时间 / 置信度。\n"
                + "3. 若检索结果不足，请明确说明不确定点，不要编造政策或价格。";
    }
}
