package com.example.sell.node;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.sell.common.AiRequestContextHolder;
import com.example.sell.service.impl.AiChatPersistenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通用聊天 Agent 节点（NORMAL/SECURITY/OTHER 分类）
 * 纯对话，无工具、无 HITL
 *
 * 输入状态：QUESTION、SESSION_ID、MEMORY_CONTEXT
 * 输出状态：AGENT_RESULT
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class GeneralChatNode implements NodeActionWithConfig {

    /** 与 AgentConfig 中 SummarizationHook.summaryPrefix 保持严格一致 */
    private static final String SUMMARY_PREFIX = "以下是最近的对话：";

    @Resource(name = "generalAgent")
    private ReactAgent generalAgent;

    @Resource
    private AiChatPersistenceService aiChatPersistenceService;

    @Override
    public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
        String question = state.value(StateKeys.QUESTION, String.class).orElse("");
        String sessionId = state.value(StateKeys.SESSION_ID, String.class).orElse("");
        String memoryContext = state.value(StateKeys.MEMORY_CONTEXT, String.class).orElse("");

        log.info("[通用聊天Agent] 开始处理, sessionId={}, 问题: {}", sessionId, question);

        try {
            // 1. 构建上下文消息
            String contextMessage = buildContextMessage(question, memoryContext);

            // 2. 通用 Agent 使用独立的 threadId 命名空间
            String threadId = "general_" + sessionId;
            RunnableConfig agentConfig = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            log.info("[通用聊天Agent] 调用 generalAgent, threadId={}", threadId);
            long startTime = System.currentTimeMillis();

            // 3. 调用 Agent（无工具、无 HITL，直接返回结果）
            Optional<NodeOutput> result;
            try {
                result = generalAgent.invokeAndGetOutput(contextMessage, agentConfig);
            } catch (Exception e) {
                // 会话损坏时切换新 threadId 重试
                if (isCorruptedSessionError(e)) {
                    String freshThreadId = "general_" + sessionId + "_fix_" + System.currentTimeMillis();
                    log.warn("[通用聊天Agent] 检测到会话状态损坏，切换新 threadId={}", freshThreadId);
                    RunnableConfig retryConfig = RunnableConfig.builder()
                            .threadId(freshThreadId)
                            .build();
                    result = generalAgent.invokeAndGetOutput(contextMessage, retryConfig);
                } else {
                    throw e;
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[通用聊天Agent] Agent 执行完成, 耗时 {}ms", elapsed);

            // 4. 提取并持久化摘要
            extractAndPersistSummary(sessionId, result);

            // 5. 提取回答
            String answer = extractAnswer(result);
            answer = appendCitations(answer);
            log.info("[通用聊天Agent] AI 回答（前200字）: {}",
                    answer.length() > 200 ? answer.substring(0, 200) + "..." : answer);

            return Map.of(
                    StateKeys.AGENT_RESULT, answer,
                    StateKeys.HITL_INTERRUPTED, false
            );
        } catch (Exception e) {
            log.error("[通用聊天Agent] 执行失败, sessionId={}", sessionId, e);
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
     * 从 Agent 结果中提取 AI 回答
     */
    private String extractAnswer(Optional<NodeOutput> result) {
        if (result.isEmpty()) {
            return "AI 未返回任何结果";
        }
        try {
            OverAllState agentState = result.get().state();
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
            log.warn("[通用聊天Agent] 提取回答失败", e);
        }
        return result.get().toString();
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
            log.warn("[通用聊天Agent] 提取并持久化摘要失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 判断异常是否为会话状态损坏
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

    private String appendCitations(String answer) {
        String citationBlock = AiRequestContextHolder.renderCitationBlock(3);
        if (!StringUtils.hasText(citationBlock)) {
            return answer;
        }
        if (!StringUtils.hasText(answer)) {
            return citationBlock;
        }
        if (answer.contains("参考来源：")) {
            return answer;
        }
        return answer.trim() + "\n\n" + citationBlock;
    }
}
