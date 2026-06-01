package com.example.sell.service;

import com.example.sell.dto.AiProductChatRequest;
import com.example.sell.vo.AiChatHistoryVo;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 商品 AI 客服服务
 */
public interface AiProductCustomerService {

    /**
     * 响应式流式处理商品查询会话（支持 HITL）
     *
     * @return SSE 事件流，事件类型：meta / thought / hitl / final / tool / done / error
     */
    Flux<ServerSentEvent<String>> streamProductCustomer(AiProductChatRequest request);

    /**
     * 查询会话历史消息
     */
    AiChatHistoryVo getChatHistory(String sessionId, Integer limit);
}
