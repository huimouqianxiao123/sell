package com.example.sell.node;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.sell.service.Imp.AiChatPersistenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 商品客服 Agent 节点（MARKETING 分类）
 * 负责：调用 marketingAgent 处理商品相关问题
 *
 * 核心功能：
 * 1. 构建上下文消息（注入 memory_context）
 * 2. 调用 ReactAgent 并处理结果
 * 3. HITL 中断处理（通过 state 状态传递）
 * 4. 会话损坏自动恢复（切换新 threadId 重试）
 * 5. SummarizationHook 摘要提取与持久化
 *
 * 输入状态：QUESTION、SESSION_ID、MEMORY_CONTEXT
 * 输出状态：AGENT_RESULT、HITL_INTERRUPTED、HITL_FEEDBACKS、ORIGINAL_MESSAGE
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class AgentNode implements NodeActionWithConfig {

    /** 与 AgentConfig 中 SummarizationHook.summaryPrefix 保持严格一致 */
    private static final String SUMMARY_PREFIX = "以下是最近的对话：";

    @Resource(name = "marketingAgent")
    private ReactAgent marketingAgent;

    @Resource
    private AiChatPersistenceService aiChatPersistenceService;

    @Override
    public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
        String question = state.value(StateKeys.QUESTION, String.class).orElse("");
        String sessionId = state.value(StateKeys.SESSION_ID, String.class).orElse("");
        String memoryContext = state.value(StateKeys.MEMORY_CONTEXT, String.class).orElse("");

        log.info("[商品客服Agent] 开始处理, sessionId={}, 问题: {}", sessionId, question);

        try {
            // 1. 构建上下文消息（将历史对话作为前缀注入）
            String contextMessage = buildContextMessage(question, memoryContext);

            // 2. 获取有效 threadId
            String activeThreadId = Optional.ofNullable(
                    aiChatPersistenceService.getActiveThreadId(sessionId)).orElse(sessionId);
            RunnableConfig agentConfig = RunnableConfig.builder()
                    .threadId(activeThreadId)
                    .build();

            log.info("[商品客服Agent] 调用 marketingAgent, threadId={}", activeThreadId);
            long startTime = System.currentTimeMillis();

            // 3. 调用 Agent
            Optional<NodeOutput> result;
            try {
                result = marketingAgent.invokeAndGetOutput(contextMessage, agentConfig);
            } catch (Exception e) {
                // 检测会话状态损坏，自动切换新 threadId 重试
                if (isCorruptedSessionError(e)) {
                    String freshThreadId = sessionId + "_fix_" + System.currentTimeMillis();
                    aiChatPersistenceService.saveActiveThreadId(sessionId, freshThreadId);
                    log.warn("[商品客服Agent] 检测到会话状态损坏，切换新 threadId={} 重试", freshThreadId);
                    RunnableConfig retryConfig = RunnableConfig.builder()
                            .threadId(freshThreadId)
                            .build();
                    result = marketingAgent.invokeAndGetOutput(contextMessage, retryConfig);
                    log.info("[商品客服Agent] 使用新 threadId={} 重试成功", freshThreadId);
                } else {
                    throw e;
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[商品客服Agent] Agent 执行完成, 耗时 {}ms, 有结果={}", elapsed, result.isPresent());

            // 4. 提取并持久化 SummarizationHook 摘要
            extractAndPersistSummary(sessionId, result);

            // 5. 处理结果
            return handleAgentResult(sessionId, question, result);
        } catch (Exception e) {
            log.error("[商品客服Agent] 执行失败, sessionId={}", sessionId, e);
            return Map.of(
                    StateKeys.AGENT_RESULT, "AI 处理失败：" + e.getMessage(),
                    StateKeys.ERROR, e.getMessage()
            );
        }
    }

    /**
     * 构建注入历史上下文的消息
     */
    private String buildContextMessage(String question, String memoryContext) {
        if (!StringUtils.hasText(memoryContext)) {
            return question;
        }
        return "[以下是用户此前的对话历史，供你参考]\n" + memoryContext + "\n\n" +
                "[当前问题]\n" + question;
    }

    /**
     * 处理 Agent 执行结果
     */
    private Map<String, Object> handleAgentResult(String sessionId, String originalMessage,
                                                   Optional<NodeOutput> result) {
        if (result.isEmpty()) {
            log.warn("[商品客服Agent] Agent 返回空结果, sessionId={}", sessionId);
            return Map.of(StateKeys.AGENT_RESULT, "AI 未返回任何结果");
        }

        NodeOutput output = result.get();
        Map<String, Object> resultMap = new LinkedHashMap<>();

        if (output instanceof InterruptionMetadata metadata) {
            // HITL 中断 —— 提取工具调用信息
            log.info("[商品客服Agent] HITL 中断触发, sessionId={}", sessionId);

            // 保存 HITL 前的干净对话历史
            extractAndSavePreHitlHistory(sessionId, result);

            // 预切换新 threadId，避免后续对话加载损坏状态
            String nextThreadId = sessionId + "_after_hitl_" + System.currentTimeMillis();
            aiChatPersistenceService.saveActiveThreadId(sessionId, nextThreadId);

            // 提取工具调用参数
            List<Map<String, Object>> feedbacks = metadata.toolFeedbacks().stream()
                    .map(tf -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("toolName", tf.getName());
                        Object rawArgs = tf.getArguments();
                        String argsJson = (rawArgs instanceof String)
                                ? (String) rawArgs
                                : JSONUtil.toJsonStr(rawArgs);
                        m.put("arguments", argsJson);
                        m.put("description", tf.getDescription());
                        return m;
                    })
                    .collect(Collectors.toList());

            // 保存 HITL 待确认状态到 Redis
            aiChatPersistenceService.savePendingHitl(sessionId, originalMessage, feedbacks);
            aiChatPersistenceService.saveSystemEvent(
                    sessionId, "hitl", "等待用户确认工具调用", Map.of("feedbacks", feedbacks));

            resultMap.put(StateKeys.HITL_INTERRUPTED, true);
            resultMap.put(StateKeys.HITL_FEEDBACKS, JSONUtil.toJsonStr(feedbacks));
            resultMap.put(StateKeys.ORIGINAL_MESSAGE, originalMessage);
            resultMap.put(StateKeys.AGENT_RESULT, "");
        } else {
            // 正常结果 —— 提取 AI 回答
            String answer = extractAnswer(output);
            log.info("[商品客服Agent] AI 回答（前200字）: {}",
                    answer.length() > 200 ? answer.substring(0, 200) + "..." : answer);
            resultMap.put(StateKeys.AGENT_RESULT, answer);
            resultMap.put(StateKeys.HITL_INTERRUPTED, false);
        }

        return resultMap;
    }

    /**
     * 从 NodeOutput 提取 AI 最终回答
     */
    private String extractAnswer(NodeOutput output) {
        try {
            OverAllState agentState = output.state();
            Optional<Object> messagesOpt = agentState.value("messages");
            if (messagesOpt.isPresent() && messagesOpt.get() instanceof List<?> list) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    Object msg = list.get(i);
                    if (msg instanceof AssistantMessage am && StringUtils.hasText(am.getText())) {
                        return am.getText();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[商品客服Agent] 从 NodeOutput.state 提取回答失败", e);
        }
        return output.toString();
    }

    /**
     * 提取并持久化 SummarizationHook 生成的摘要
     */
    private void extractAndPersistSummary(String sessionId, Optional<NodeOutput> result) {
        if (result.isEmpty()) {
            return;
        }
        try {
            OverAllState agentState = result.get().state();
            Optional<Object> messagesOpt = agentState.value("messages");
            if (messagesOpt.isEmpty() || !(messagesOpt.get() instanceof List<?> messages)) {
                return;
            }
            for (Object msg : messages) {
                if (msg instanceof SystemMessage sm) {
                    String text = sm.getText();
                    if (StringUtils.hasText(text) && text.startsWith(SUMMARY_PREFIX)) {
                        aiChatPersistenceService.saveSummaryIfChanged(sessionId, text);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[商品客服Agent] 提取并持久化摘要失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 提取 HITL 中断前的干净对话历史并保存到 Redis
     */
    private void extractAndSavePreHitlHistory(String sessionId, Optional<NodeOutput> result) {
        if (result.isEmpty()) {
            return;
        }
        try {
            OverAllState agentState = result.get().state();
            Optional<Object> messagesOpt = agentState.value("messages");
            if (messagesOpt.isEmpty() || !(messagesOpt.get() instanceof List<?> messages)) {
                return;
            }
            StringBuilder history = new StringBuilder();
            for (Object msg : messages) {
                if (msg instanceof UserMessage userMsg) {
                    String text = userMsg.getText();
                    if (text == null || text.startsWith("[背景：") || text.startsWith("[以下是用户此前") || text.startsWith("用户的原始问题:")) {
                        continue;
                    }
                    String truncated = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                    history.append("用户: ").append(truncated).append("\n");
                } else if (msg instanceof AssistantMessage assistantMsg) {
                    if (assistantMsg.getToolCalls() != null && !assistantMsg.getToolCalls().isEmpty()) {
                        continue;
                    }
                    String text = assistantMsg.getText();
                    if (StringUtils.hasText(text)) {
                        String truncated = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                        history.append("助手: ").append(truncated).append("\n");
                    }
                }
            }
            String historyText = history.toString().trim();
            if (StringUtils.hasText(historyText)) {
                aiChatPersistenceService.savePreHitlHistory(sessionId, historyText);
            }
        } catch (Exception e) {
            log.warn("[商品客服Agent] 提取并保存 HITL 前历史失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 判断异常是否为会话状态损坏导致的 tool_calls 缺少 tool response 错误
     */
    private boolean isCorruptedSessionError(Exception e) {
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("must be followed by tool messages")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
