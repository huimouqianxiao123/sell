package com.example.sell.controller;

import com.example.sell.common.R;
import com.example.sell.domain.Dto.AiProductChatRequest;
import com.example.sell.domain.vo.AiChatHistoryVo;
import com.example.sell.service.AiProductCustomerService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI 商品客服控制器
 *
 * @author 屈轩
 */
@RestController
@RequestMapping("/ai")
public class ChatController {

    @Resource
    private AiProductCustomerService aiProductCustomerService;

    /**
     * 商品 AI 客服（响应式流式）
     * 前端通过 EventSource / fetch 接收 SSE 事件流
     */
    @PostMapping(value = "/product/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> productChatStream(@RequestBody AiProductChatRequest request) {
        return aiProductCustomerService.streamProductCustomer(request);
    }

    /**
     * 查询 AI 会话历史
     */
    @GetMapping("/product/chat/history")
    public R<AiChatHistoryVo> getChatHistory(@RequestParam String sessionId,
                                             @RequestParam(required = false) Integer limit) {
        return R.ok(aiProductCustomerService.getChatHistory(sessionId, limit));
    }
}
