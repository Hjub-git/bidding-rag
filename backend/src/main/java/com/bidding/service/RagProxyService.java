package com.bidding.service;

import com.bidding.entity.RagQaLog;
import com.bidding.mapper.RagQaLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 Java 侧的问答请求流式代理到 Python 检索服务，并把 SSE 帧原样回吐给浏览器。
 *
 * <p>为什么用 JDK 17 原生 {@link HttpClient}，不引 WebFlux/WebClient：
 * 本项目是 Servlet 栈（Spring MVC），为了一个下游 SSE 调用引入整套响应式依赖，
 * 换来的复杂度远大于收益；JDK 17 的 HttpClient 已能满足流式读取。
 *
 * <p>为什么不用 RestTemplate：它默认把响应体整体读进内存，流式场景等于把
 * 「边生成边推送」退化成「全生成完再推」，首 Token 延迟直接等于总生成时间。
 *
 * <p>强制 HTTP/1.1：JDK HttpClient 默认 HTTP/2，对 http:// 会先发 h2c upgrade，
 * 而 uvicorn 不支持 → 直接回 400（Python 日志：Unsupported upgrade request /
 * Invalid HTTP request received）。这个坑只在真正转发时才会暴露。
 *
 * <p>审计：转发线程顺便解析 {@code sources} / {@code done} 帧，把本轮问答写进
 * {@code rag_qa_log}；客户端断连且没收到 done 时记 {@code aborted=1}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagProxyService {

    private final ObjectMapper objectMapper;
    private final RagQaLogMapper ragQaLogMapper;

    /** Python 服务地址。只绑本机回环，不对内网/公网暴露。 */
    @Value("${rag.python.base-url:http://127.0.0.1:8000}")
    private String pythonBaseUrl;

    /**
     * Python 内部服务令牌：Python 侧除 {@code /api/health} 外所有接口都要求
     * {@code X-Internal-Token}。这个值必须与 Python 的 {@code Config.INTERNAL_TOKEN}
     * 逐字符一致，否则全链路 401。两边都读同一个环境变量 {@code RAG_INTERNAL_TOKEN}。
     */
    @Value("${rag.internal-token:rag-internal-dev-token-2026}")
    private String internalToken;

    /** 转发线程池：问答是长连接，不能占用 Tomcat 请求线程。 */
    private static final ExecutorService FORWARD_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "rag-proxy");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            // 必须显式 HTTP/1.1，见类注释
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 发起一次流式问答。
     *
     * @param tenderId 已由 {@code RagController#assertCanAsk} 校验过；这里只做透传，不再判权限
     * @param traceId  由 Java 生成，随 {@code X-Trace-Id} 传给 Python，用于三处 grep 同一行
     * @param userId   提问人（审计用；异步线程里拿不到 SecurityContext，必须在 Controller 取好传进来）
     */
    public SseEmitter streamChat(Long tenderId, String query,
                                 List<Map<String, Object>> chatHistory, String traceId, Long userId) {
        // 0L = 不限总时长。TODO：换成「30s 无 token 即断开」的 idle 看门狗。
        SseEmitter emitter = new SseEmitter(0L);

        AtomicReference<CompletableFuture<HttpResponse<InputStream>>> upstreamRef = new AtomicReference<>();
        AtomicReference<InputStream> upstreamBodyRef = new AtomicReference<>();
        AtomicBoolean aborted = new AtomicBoolean(false);
        // 是否收到结束标记（[DONE] 或 {"type":"done"}）。用于区分「正常结束」与「客户端断连」
        AtomicBoolean sawDone = new AtomicBoolean(false);
        // 本轮是否已落过审计。断连时 onError 与 onCompletion 可能都触发，
        // 不加这个 CAS 会落两条 aborted（实测踩到过）。
        AtomicBoolean audited = new AtomicBoolean(false);
        // 最后一次 sources 帧的 items（JSON 文本），审计要存
        AtomicReference<String> sourcesJson = new AtomicReference<>(null);

        Runnable abortUpstream = () -> {
            aborted.set(true);
            CompletableFuture<HttpResponse<InputStream>> future = upstreamRef.getAndSet(null);
            if (future != null) {
                future.cancel(true);
            }
            InputStream body = upstreamBodyRef.getAndSet(null);
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // 关闭上游流失败无需上报，连接本身已经被丢弃
                }
            }
        };

        // 客户端断开/超时/出错：掐掉上游，并在「没收到 done」时记一条 aborted=1
        Runnable onClientGone = () -> {
            abortUpstream.run();
            if (!sawDone.get() && audited.compareAndSet(false, true)) {
                recordAborted(tenderId, query, traceId, userId, sourcesJson.get());
            }
        };
        emitter.onCompletion(onClientGone);
        emitter.onTimeout(onClientGone);
        emitter.onError(t -> onClientGone.run());

        FORWARD_POOL.execute(() -> {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tender_id", tenderId);
                payload.put("query", query);
                payload.put("chat_history", chatHistory == null ? List.of() : chatHistory);

                HttpRequest request = HttpRequest.newBuilder(URI.create(pythonBaseUrl + "/api/chat"))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/json")
                        .header("X-Trace-Id", traceId)
                        // 内部服务令牌：没有它 Python 直接 401
                        .header("X-Internal-Token", internalToken)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                        .build();

                CompletableFuture<HttpResponse<InputStream>> upstream =
                        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
                upstreamRef.set(upstream);
                HttpResponse<InputStream> response = upstream.get();
                InputStream upstreamBody = response.body();
                upstreamBodyRef.set(upstreamBody);

                if (response.statusCode() != 200) {
                    log.warn("trace_id={} 上游返回 {}，转成 error 帧", traceId, response.statusCode());
                    sendError(emitter, "检索服务暂时不可用");
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(upstreamBody, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // 只转发 data 帧：data:（带空格）与 data:（无空格）两种写法都认；
                        // event: / id: / : heartbeat 等其它行直接跳过，不算错误。
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String data = line.substring(5);
                        if (data.startsWith(" ")) {
                            data = data.substring(1);
                        }
                        // 结束标记有两种：旧版 `[DONE]` 与 v2 的 `{"type":"done"}`。
                        // 只认 [DONE] 会让每次成功回答后都误判成断流、补一条 error 帧。
                        if ("[DONE]".equals(data.trim())) {
                            sawDone.set(true);
                        } else if (data.startsWith("{")) {
                            try {
                                JsonNode node = objectMapper.readTree(data);
                                String type = node.has("type") ? node.get("type").asText() : "";
                                if ("done".equals(type)) {
                                    sawDone.set(true);
                                    if (audited.compareAndSet(false, true)) {
                                        recordQa(node, tenderId, query, traceId, userId, sourcesJson.get());
                                    }
                                } else if ("sources".equals(type) && node.has("items")) {
                                    sourcesJson.set(node.get("items").toString());
                                }
                            } catch (Exception ignore) {
                                // 非 JSON 帧（旧版纯文本），忽略即可
                            }
                        }
                        emitter.send(SseEmitter.event().data(data));
                    }
                }

                if (sawDone.get()) {
                    emitter.complete();
                } else if (!aborted.get()) {
                    // 上游没发结束标记就断流：不能当成正常结束，否则前端会收到半截回答却以为成功
                    log.warn("trace_id={} 上游未发送 done/[DONE]，按断流处理", traceId);
                    sendError(emitter, "检索服务中断，回答可能不完整");
                }

            } catch (Exception e) {
                if (aborted.get()) {
                    // 客户端已断开，上游被主动取消，此时再发 error 帧没有意义
                    log.debug("trace_id={} 客户端已断开，转发中止: {}", traceId, e.toString());
                } else {
                    log.warn("trace_id={} 转发失败: {}", traceId, e.toString());
                    sendError(emitter, "检索服务连接失败");
                }
            }
        });

        return emitter;
    }

    /** 正常/拒答结束：落一行审计（refused 由 Python 的 stats 决定）。 */
    private void recordQa(JsonNode doneNode, Long tenderId, String query,
                          String traceId, Long userId, String sourcesJson) {
        try {
            JsonNode stats = doneNode.path("stats");
            RagQaLog row = new RagQaLog();
            row.setTraceId(traceId);
            row.setTenderId(tenderId);
            row.setUserId(userId);
            row.setQueryText(query);
            row.setHitCount(stats.path("hit_count").asInt(0));
            row.setSources(sourcesJson);
            row.setAnswerLen(stats.path("answer_len").asInt(0));
            row.setLatencyMs(stats.path("latency_ms").asInt(0));
            row.setRefused(stats.path("refused").asBoolean(false) ? 1 : 0);
            row.setAborted(0);
            ragQaLogMapper.insert(row);
            log.info("trace_id={} 审计落库 hit_count={} refused={} sources={}",
                    traceId, row.getHitCount(), row.getRefused(), sourcesJson != null);
        } catch (Exception e) {
            // 审计失败不能影响用户拿到答案
            log.warn("trace_id={} 审计写入失败: {}", traceId, e.toString());
        }
    }

    /** 客户端断连且没收到 done：落一行 aborted=1。 */
    private void recordAborted(Long tenderId, String query, String traceId,
                               Long userId, String sourcesJson) {
        try {
            RagQaLog row = new RagQaLog();
            row.setTraceId(traceId);
            row.setTenderId(tenderId);
            row.setUserId(userId);
            row.setQueryText(query);
            row.setHitCount(0);
            row.setSources(sourcesJson);
            row.setAnswerLen(0);
            row.setLatencyMs(0);
            row.setRefused(0);
            row.setAborted(1);
            ragQaLogMapper.insert(row);
            log.info("trace_id={} 客户端断连，审计落库 aborted=1", traceId);
        } catch (Exception e) {
            log.warn("trace_id={} 断连审计写入失败: {}", traceId, e.toString());
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "error");
            err.put("code", "UPSTREAM_UNAVAILABLE");
            err.put("message", message);
            // SSE 帧约定 硬约束：只用 data: 通道，不带 event: 名。
            // event: 名跨代理/跨网关最容易丢，前端一律按 payload.type 分流。
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(err)));
            emitter.complete();
        } catch (Exception ignored) {
            emitter.completeWithError(ignored);
        }
    }
}
