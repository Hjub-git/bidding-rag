package com.bidding.controller;

import com.bidding.common.BusinessException;
import com.bidding.common.ResultCode;
import com.bidding.dto.RagChatRequest;
import com.bidding.security.SecurityUtil;
import com.bidding.service.RagPermissionService;
import com.bidding.service.RagProxyService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * 招标文件智能问答的唯一入口。
 *
 * <p>权限原则（一句话）：「能不能问」由 {@link RagPermissionService} 判（问答与上传共用
 * 同一份判定）；「只能看到本项目」由 Python 用这里注入的 {@code tender_id} 强制约束。
 *
 * <p>路由刻意放在 {@code /api/rag/**} 命名空间，与现有
 * {@code GET /api/tenders/} 的 permitAll 前缀完全不重叠**。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagPermissionService ragPermissionService;
    private final RagProxyService ragProxyService;

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody(required = false) RagChatRequest request,
                           HttpServletResponse response) {
        try {
            return doChat(request, response);
        } catch (BusinessException e) {
            // SSE 端点：错误也必须以事件流返回，否则 Accept: text/event-stream 的客户端
            // 会让 Spring 内容协商失败（HttpMediaTypeNotAcceptableException → 500）。
            return sseError(e.getCode(), e.getMessage());
        }
    }

    private SseEmitter doChat(RagChatRequest request, HttpServletResponse response) {
        if (request == null || request.getTenderId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "tender_id 不能为空");
        }
        if (!StringUtils.hasText(request.getQuery())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
        }

        // 唯一权限入口：先判权，再碰任何检索资源
        ragPermissionService.assertCanAsk(request.getTenderId());

        String traceId = UUID.randomUUID().toString().replace("-", "");
        response.setHeader("X-Trace-Id", traceId);

        // userId 必须在这里取：SSE 是异步的，转发线程里 SecurityContext 已经没了。
        // 审计 rag_qa_log 的 user_id 就靠这个值（审计三段式）。
        Long userId = SecurityUtil.getCurrentUserId();
        log.info("trace_id={} tender_id={} user_id={} role={} event=ask",
                traceId, request.getTenderId(), userId, SecurityUtil.getCurrentRole());

        return ragProxyService.streamChat(
                request.getTenderId(), request.getQuery(), request.getChatHistory(), traceId, userId);
    }

    /** 以 SSE 事件流的形式返回错误（code/message 与 Result 结构一致，前端可复用 error 帧处理） */
    private SseEmitter sseError(int code, String message) {
        SseEmitter emitter = new SseEmitter(10_000L);
        try {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", "error");
            payload.put("code", code);
            payload.put("message", message);
            emitter.send(SseEmitter.event().data(payload));
        } catch (Exception ignored) {
            // 客户端已断开，忽略
        }
        emitter.complete();
        return emitter;
    }
}
