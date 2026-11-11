package com.bidding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 问答请求体。
 *
 * <p>字段名用 {@code @JsonProperty} 显式钉成 snake_case：项目全局配了
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}，但显式声明能保证
 * 「换配置也不串字段」，且这份契约要和前端 / Python 三边对齐。
 */
@Data
public class RagChatRequest {

    /** 必填：只能在哪个项目内检索。这个值由 Java 注入 Python，是隔离的唯一依据。 */
    @JsonProperty("tender_id")
    private Long tenderId;

    /** 必填：用户问题 */
    @JsonProperty("query")
    private String query;

    /** 可选：对话历史，[{role: user|assistant, content: ...}]，原样透传给 Python */
    @JsonProperty("chat_history")
    private List<Map<String, Object>> chatHistory;
}
