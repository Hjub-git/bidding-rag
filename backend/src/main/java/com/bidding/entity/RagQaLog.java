package com.bidding.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答审计表。每一轮问答落一行，拒答与断连也要落（否则事后无法区分"拒答"和"答错"）。
 *
 * <p>字段来源三处：
 * <ul>
 *   <li>Java：{@code trace_id / tender_id / user_id / query_text}</li>
 *   <li>Python {@code done} 帧的 {@code stats}：{@code hit_count / answer_len / latency_ms / refused}</li>
 *   <li>Java 自己判：{@code aborted}（客户端断连且没收到 done）</li>
 * </ul>
 */
@Data
@TableName("rag_qa_log")
public class RagQaLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private Long tenderId;
    private Long userId;
    private String queryText;

    /** 本项目命中 chunk 数；0 表示拒答 */
    private Integer hitCount;

    /** 命中的 source/page 列表（JSON 文本），供事后复核 */
    private String sources;

    private Integer answerLen;
    private Integer latencyMs;

    /** 1=拒答（本项目未收录），0=正常作答 */
    private Integer refused;

    /** 1=客户端断连，与 refused 正交 */
    private Integer aborted;

    private LocalDateTime createdAt;
}
