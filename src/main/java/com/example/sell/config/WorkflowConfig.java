package com.example.sell.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.example.sell.node.AgentNode;
import com.example.sell.node.GeneralChatNode;
import com.example.sell.node.MemoryNode;
import com.example.sell.node.QuestionNotifyNode;
import com.example.sell.node.SaveNode;
import com.example.sell.node.StateKeys;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig.node_async;

/**
 * AI 客服工作流配置
 * 使用 Spring AI Alibaba Graph 构建 StateGraph 工作流
 *
 * @author 屈轩
 */
@Slf4j
@Configuration
public class WorkflowConfig {

    @Resource
    private QuestionNotifyNode questionNotifyNode;

    @Resource
    private MemoryNode memoryNode;

    @Resource
    private AgentNode agentNode;

    @Resource
    private GeneralChatNode generalChatNode;

    @Resource
    private SaveNode saveNode;

    @Bean
    public CompiledGraph aiCustomerServiceGraph() throws Exception {
        KeyStrategyFactory keyStrategyFactory = () -> Map.of(
                StateKeys.QUESTION, KeyStrategy.REPLACE,
                StateKeys.SESSION_ID, KeyStrategy.REPLACE,
                StateKeys.QUESTION_STYLE, KeyStrategy.REPLACE,
                StateKeys.MEMORY_CONTEXT, KeyStrategy.REPLACE,
                StateKeys.AGENT_RESULT, KeyStrategy.REPLACE,
                StateKeys.HITL_INTERRUPTED, KeyStrategy.REPLACE,
                StateKeys.HITL_FEEDBACKS, KeyStrategy.REPLACE,
                StateKeys.ORIGINAL_MESSAGE, KeyStrategy.REPLACE,
                StateKeys.ERROR, KeyStrategy.REPLACE,
                "messages", KeyStrategy.APPEND
        );

        StateGraph graph = new StateGraph("ai_customer_service", keyStrategyFactory)
                .addNode("classify", node_async(questionNotifyNode))
                .addNode("memory", node_async(memoryNode))
                .addNode("marketing_agent", node_async(agentNode))
                .addNode("general_agent", node_async(generalChatNode))
                .addNode("save", node_async(saveNode))
                .addEdge(START, "classify")
                .addEdge("classify", "memory")
                .addConditionalEdges("memory",
                        (AsyncEdgeAction) (state) -> {
                            String style = state.value(StateKeys.QUESTION_STYLE, String.class)
                                    .orElse("OTHER");
                            log.info("[工作流路由] question_style={}, 路由到={}",
                                    style, "MARKETING".equals(style) ? "marketing_agent" : "general_agent");
                            String target = "MARKETING".equals(style) ? "marketing_agent" : "general_agent";
                            return CompletableFuture.completedFuture(target);
                        },
                        Map.of(
                                "marketing_agent", "marketing_agent",
                                "general_agent", "general_agent"
                        ))
                .addEdge("marketing_agent", "save")
                .addEdge("general_agent", "save")
                .addEdge("save", END);

        CompiledGraph compiledGraph = graph.compile();
        log.info("[工作流] AI 客服工作流图编译完成");

        return compiledGraph;
    }
}
