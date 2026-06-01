package com.example.sell.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.sell.ai.tool.ProductQueryRequest;
import com.example.sell.ai.tool.ProductQueryTool;
import com.example.sell.common.AiRequestContextHolder;
import com.example.sell.dto.AiProductChatRequest;
import com.example.sell.entity.AiChatMessage;
import com.example.sell.vo.AiChatHistoryVo;
import com.example.sell.vo.AiChatMessageVo;
import com.example.sell.node.StateKeys;
import com.example.sell.common.UserContext;
import com.example.sell.common.UserInfo;
import com.example.sell.service.AiProductCustomerService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 商品 AI 客服服务实现（重构版 —— Graph Workflow）
 *
 * 核心变化：
 * - 主流程委托给 CompiledGraph.invoke()，不再直接操作 ReactAgent
 * - 工作流拓扑：classify → memory → [条件路由] → agent/general → save
 * - 保留 SSE 流式输出、HITL 恢复/取消逻辑
 *
 * 工作流节点分工：
 * - QuestionNotifyNode：问题分类（MARKETING/NORMAL/SECURITY/OTHER）
 * - MemoryNode：加载历史对话上下文
 * - AgentNode：商品客服 Agent（MARKETING，含工具+HITL）
 * - GeneralChatNode：通用聊天 Agent（其他分类，无工具）
 * - SaveNode：持久化 Agent 回答到 MySQL + Redis
 *
 * @author 屈轩
 */
@Slf4j
@Service
public class AiProductCustomerServiceImp implements AiProductCustomerService {

    @Resource
    private CompiledGraph aiCustomerServiceGraph;

    @Resource(name = "marketingAgent")
    private ReactAgent marketingAgent;

    @Resource(name = "ioTaskExecutor")
    private ThreadPoolTaskExecutor ioTaskExecutor;

    @Resource
    private AiChatPersistenceService aiChatPersistenceService;

    @Resource
    private AiChatMemoryService aiChatMemoryService;

    @Resource
    private ProductQueryTool productQueryTool;

    @Value("${ai.rag.memory-window-days:30}")
    private int ragMemoryWindowDays;

    @Override
    public Flux<ServerSentEvent<String>> streamProductCustomer(AiProductChatRequest request) {
        // 在 HTTP 请求线程中获取用户信息（ThreadLocal 仅在此线程可用）
        UserInfo userInfo = UserContext.getUserInfo();
        String userId = (userInfo != null && userInfo.getId() != null)
                ? String.valueOf(userInfo.getId()) : "";

        return Flux.create(sink -> {
            ioTaskExecutor.execute(() -> {
                try {
                    doStream(request, sink, userId);
                } catch (Exception e) {
                    log.error("[AI客服] 处理异常", e);
                    emitEvent(sink, "error", Map.of("message", "AI 客服处理失败，请稍后重试"));
                    sink.complete();
                }
            });
        });
    }

    @Override
    public AiChatHistoryVo getChatHistory(String sessionId, Integer limit) {
        if (!StringUtils.hasText(sessionId)) {
            throw new RuntimeException("sessionId不能为空");
        }
        int pageSize = (limit == null || limit <= 0) ? 50 : Math.min(limit, 200);

        List<AiChatMessage> messageList = aiChatPersistenceService.findRecentMessages(sessionId, pageSize);
        List<AiChatMessageVo> list = messageList.stream()
                .sorted(Comparator.comparing(AiChatMessage::getId))
                .map(item -> AiChatMessageVo.builder()
                        .id(item.getId())
                        .sessionId(item.getSessionId())
                        .role(item.getRole())
                        .eventType(item.getEventType())
                        .content(item.getContent())
                        .extraJson(item.getExtraJson())
                        .createTime(item.getCreateTime())
                        .build())
                .collect(Collectors.toList());

        return AiChatHistoryVo.builder()
                .sessionId(sessionId)
                .total(list.size())
                .list(list)
                .build();
    }

    // ==================== 主流程 ====================

    private void doStream(AiProductChatRequest request, FluxSink<ServerSentEvent<String>> sink, String userId) {
        String message = request.getMessage();
        Boolean hitlApproved = request.getHitlApproved();

        // hitlApproved 为 null 时是正常聊天，必须有消息内容
        if (hitlApproved == null && !StringUtils.hasText(message)) {
            emitEvent(sink, "error", Map.of("message", "请输入要查询的商品信息"));
            sink.complete();
            return;
        }

        // 生成或复用 sessionId
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId().trim()
                : IdUtil.fastSimpleUUID();

        emitEvent(sink, "meta", Map.of("sessionId", sessionId));
        aiChatPersistenceService.touchSession(sessionId);

        AiRequestContextHolder.init(userId, sessionId, ragMemoryWindowDays);
        try {
            if (Boolean.TRUE.equals(hitlApproved)) {
                handleHitlResume(sink, sessionId, message, userId);
            } else if (Boolean.FALSE.equals(hitlApproved)) {
                handleHitlCancel(sink, sessionId, userId);
            } else {
                cleanUpStalePendingHitl(sessionId, userId);
                executeWorkflow(sink, sessionId, message, userId);
            }
        } finally {
            AiRequestContextHolder.clear();
        }
    }

    // ==================== 工作流执行 ====================

    /**
     * 执行 Graph Workflow 主流程
     * classify → memory → [条件路由] → agent/general → save
     */
    private void executeWorkflow(FluxSink<ServerSentEvent<String>> sink, String sessionId, String message, String userId) {
        emitEvent(sink, "thought", Map.of("content", "正在分析您的问题..."));

        log.info("[AI客服] ========== 开始工作流 ==========");
        log.info("[AI客服] sessionId={}, userId={}, 用户输入: {}", sessionId, userId, message);

        try {
            // 1. 保存用户消息
            aiChatPersistenceService.saveUserMessage(sessionId, userId, message);
            aiChatMemoryService.appendMessage(sessionId, "user", message);

            // 2. 构建初始状态（包含 userId 供 SaveNode 用于用户画像提取）
            Map<String, Object> initialState = Map.of(
                    StateKeys.QUESTION, message,
                    StateKeys.SESSION_ID, sessionId,
                    StateKeys.USER_ID, userId
            );

            // 3. 执行工作流
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .build();

            long startTime = System.currentTimeMillis();
            Optional<OverAllState> result = aiCustomerServiceGraph.invoke(initialState, config);
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("[AI客服] 工作流执行完成, 耗时 {}ms, 有结果={}", elapsed, result.isPresent());

            if (result.isEmpty()) {
                emitEvent(sink, "error", Map.of("message", "AI 未返回任何结果"));
                sink.complete();
                return;
            }

            // 4. 从最终 state 提取结果
            OverAllState finalState = result.get();

            // 5. 处理 HITL 中断
            boolean hitlInterrupted = finalState.value(StateKeys.HITL_INTERRUPTED, Boolean.class).orElse(false);
            if (hitlInterrupted) {
                handleHitlInterruption(sink, sessionId, finalState);
            } else {
                // 正常回答（SaveNode 已持久化，此处仅推送 SSE）
                String answer = finalState.value(StateKeys.AGENT_RESULT, String.class).orElse("");
                streamText(sink, sessionId, answer);
                emitEvent(sink, "done", Map.of("sessionId", sessionId, "requiresUserInput", false));
            }
        } catch (Exception e) {
            log.error("[AI客服] 工作流执行失败, sessionId={}", sessionId, e);
            emitEvent(sink, "error", Map.of("message", "AI 处理失败：" + e.getMessage()));
        } finally {
            log.info("[AI客服] ========== 工作流结束 sessionId={} ==========", sessionId);
            sink.complete();
        }
    }

    // ==================== HITL 处理 ====================

    /**
     * 处理 HITL 中断：从 finalState 中提取工具参数，推送确认事件到前端
     */
    private void handleHitlInterruption(FluxSink<ServerSentEvent<String>> sink,
                                         String sessionId, OverAllState finalState) {
        log.info("[AI客服] HITL 中断处理, sessionId={}", sessionId);

        String feedbacksJson = finalState.value(StateKeys.HITL_FEEDBACKS, String.class).orElse("[]");
        List<?> feedbacks = JSONUtil.parseArray(feedbacksJson);

        emitEvent(sink, "hitl", Map.of(
                "sessionId", sessionId,
                "feedbacks", feedbacks,
                "message", "AI 想要执行以下查询，请确认：",
                "action", "confirm"
        ));
        emitEvent(sink, "done", Map.of(
                "sessionId", sessionId,
                "requiresUserInput", true
        ));
    }

    /**
     * HITL 恢复：用户确认后手动执行工具 + 新 threadId 调用 Agent
     */
    private void handleHitlResume(FluxSink<ServerSentEvent<String>> sink,
                                   String sessionId, String additionalMessage, String userId) {
        log.info("[AI客服] ========== HITL 恢复执行 ==========");
        log.info("[AI客服] sessionId={}, 补充信息={}", sessionId, additionalMessage);

        emitEvent(sink, "thought", Map.of("content", "正在执行查询..."));

        try {
            // 1. 从 Redis 读取待执行的工具调用信息
            JSONObject pending = aiChatPersistenceService.consumePendingHitl(sessionId);
            if (pending == null) {
                log.warn("[AI客服] HITL 待确认状态已过期或不存在, sessionId={}", sessionId);
                emitEvent(sink, "error", Map.of("message", "确认已过期，请重新提问"));
                sink.complete();
                return;
            }

            String originalMessage = pending.getStr("originalMessage");
            List<JSONObject> feedbacks = pending.getBeanList("feedbacks", JSONObject.class);

            if (feedbacks == null || feedbacks.isEmpty()) {
                log.warn("[AI客服] HITL 待执行工具列表为空, sessionId={}", sessionId);
                emitEvent(sink, "error", Map.of("message", "无待执行的查询，请重新提问"));
                sink.complete();
                return;
            }

            // 2. 手动执行工具
            JSONObject firstTool = feedbacks.get(0);
            String toolName = firstTool.getStr("toolName");
            String toolArguments = firstTool.getStr("arguments");

            log.info("[AI客服] HITL 手动执行工具: name={}, arguments={}", toolName, toolArguments);
            aiChatPersistenceService.saveUserMessage(sessionId, userId, "确认执行查询" +
                    (StringUtils.hasText(additionalMessage) ? "，补充：" + additionalMessage : ""));

            ProductQueryRequest queryRequest = JSONUtil.toBean(toolArguments, ProductQueryRequest.class);
            String toolResult = productQueryTool.apply(queryRequest);

            log.info("[AI客服] HITL 工具执行完成, 结果长度={}", toolResult.length());
            emitEvent(sink, "tool", Map.of("name", toolName, "status", "completed"));

            // 3. 加载 HITL 前的对话历史
            String preHitlHistory = aiChatPersistenceService.consumePreHitlHistory(sessionId);

            // 4. 使用新 threadId 调用 Agent
            String contextMessage = buildResumeContext(originalMessage, toolName, toolResult,
                    additionalMessage, preHitlHistory);
            String freshThreadId = sessionId + "_hitl_" + System.currentTimeMillis();

            log.info("[AI客服] HITL 使用新 threadId={} 调用 Agent", freshThreadId);
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(freshThreadId)
                    .build();

            Optional<com.alibaba.cloud.ai.graph.NodeOutput> result =
                    marketingAgent.invokeAndGetOutput(contextMessage, config);

            // 5. 提取回答并输出
            String answer = extractAnswerFromNodeOutput(result);
            answer = appendCitations(answer);
            aiChatPersistenceService.saveAssistantMessage(sessionId, userId, answer);
            aiChatMemoryService.appendMessage(sessionId, "assistant", answer);

            emitEvent(sink, "tool", Map.of("name", "queryProducts", "status", "completed"));
            streamText(sink, sessionId, answer);
            emitEvent(sink, "done", Map.of("sessionId", sessionId, "requiresUserInput", false));

            // 6. 更新活跃 threadId
            aiChatPersistenceService.saveActiveThreadId(sessionId, freshThreadId);
            log.info("[AI客服] HITL 恢复完成, 活跃 threadId 更新为 {}", freshThreadId);
        } catch (Exception e) {
            log.error("[AI客服] HITL 恢复执行失败, sessionId={}", sessionId, e);
            emitEvent(sink, "error", Map.of("message", "查询执行失败：" + e.getMessage()));
        } finally {
            log.info("[AI客服] ========== HITL 恢复执行结束 sessionId={} ==========", sessionId);
            sink.complete();
        }
    }

    /**
     * HITL 取消：清除状态并返回取消提示
     */
    private void handleHitlCancel(FluxSink<ServerSentEvent<String>> sink, String sessionId, String userId) {
        log.info("[AI客服] HITL 用户取消, sessionId={}", sessionId);
        aiChatPersistenceService.clearPendingHitl(sessionId);
        aiChatPersistenceService.saveSystemEvent(sessionId, userId, "hitl", "用户取消了工具调用", null);

        String cancelMsg = "已取消查询。您可以重新描述想查找的商品，我会重新为您搜索。";
        aiChatPersistenceService.saveAssistantMessage(sessionId, userId, cancelMsg);
        aiChatMemoryService.appendMessage(sessionId, "assistant", cancelMsg);

        streamText(sink, sessionId, cancelMsg);
        emitEvent(sink, "done", Map.of("sessionId", sessionId, "requiresUserInput", false));
        sink.complete();
    }

    // ==================== 辅助方法 ====================

    /**
     * 清理遗留的 HITL 待确认状态
     */
    private void cleanUpStalePendingHitl(String sessionId, String userId) {
        boolean hadPendingHitl = aiChatPersistenceService.hasPendingHitl(sessionId);
        if (hadPendingHitl) {
            aiChatPersistenceService.clearPendingHitl(sessionId);
            String cleanThreadId = sessionId + "_clean_" + System.currentTimeMillis();
            aiChatPersistenceService.saveActiveThreadId(sessionId, cleanThreadId);
            log.warn("[AI客服] 检测到 HITL 遗留状态，已清除并切换新 threadId={}", cleanThreadId);
            aiChatPersistenceService.saveSystemEvent(
                    sessionId, userId, "hitl", "用户放弃了上次未确认的查询，自动切换新会话", null);
        }
    }

    /**
     * 构造 HITL 恢复时的上下文消息
     */
    private String buildResumeContext(String originalMessage, String toolName,
                                      String toolResult, String additionalMessage,
                                      String preHitlHistory) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(preHitlHistory)) {
            sb.append("[背景：以下是用户此前的对话历史]\n");
            sb.append(preHitlHistory).append("\n\n");
        }
        sb.append("用户的原始问题: ").append(originalMessage).append("\n\n");
        sb.append("我已经使用 ").append(toolName).append(" 工具查询过了，查询结果如下:\n");
        sb.append(toolResult).append("\n\n");
        if (StringUtils.hasText(additionalMessage)) {
            sb.append("用户的补充说明: ").append(additionalMessage).append("\n\n");
        }
        sb.append("请直接基于以上查询结果，用友好清晰的方式回答用户的问题，不需要再次调用查询工具。");
        return sb.toString();
    }

    /**
     * 从 NodeOutput 提取 AI 最终回答
     */
    private String extractAnswerFromNodeOutput(Optional<com.alibaba.cloud.ai.graph.NodeOutput> result) {
        if (result.isEmpty()) {
            return "AI 未返回任何结果";
        }
        try {
            OverAllState state = result.get().state();
            Optional<Object> messagesOpt = state.value("messages");
            if (messagesOpt.isPresent() && messagesOpt.get() instanceof List<?> list) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    Object msg = list.get(i);
                    if (msg instanceof org.springframework.ai.chat.messages.AssistantMessage am
                            && StringUtils.hasText(am.getText())) {
                        return am.getText();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[AI客服] 从 NodeOutput 提取回答失败", e);
        }
        return result.get().toString();
    }

    /**
     * 模拟打字机效果：将文本按 chunk 逐段推送
     */
    private void streamText(FluxSink<ServerSentEvent<String>> sink, String sessionId, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        int chunkSize = 30;
        for (int start = 0; start < text.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end);
            emitEvent(sink, "final", Map.of("sessionId", sessionId, "delta", chunk));
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 向 FluxSink 发送一个 SSE 事件
     */
    private void emitEvent(FluxSink<ServerSentEvent<String>> sink, String eventName, Object payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", eventName);
        data.put("data", payload);
        try {
            sink.next(ServerSentEvent.<String>builder()
                    .event(eventName)
                    .data(JSONUtil.toJsonStr(data))
                    .build());
        } catch (Exception e) {
            log.warn("[AI客服] SSE 发送失败: {}", eventName, e);
        }
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
