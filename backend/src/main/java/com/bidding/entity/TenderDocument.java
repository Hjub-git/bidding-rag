package com.bidding.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 招标文件上传记录。
 *
 * <p>幂等键是 {@code (tender_id, doc_id)}；{@code doc_id} 由服务端分配后同时传给 Python
 * 的 {@code /api/ingest}，Python 侧再用它做 {@code doc_chunk_map} 的键 —— 两侧必须是同一个值。
 */
@Data
@TableName("tender_documents")
public class TenderDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenderId;

    /** 业务键：Python 侧 chunk 的 doc_id 与它一致 */
    private Long docId;

    private String fileName;

    /** 落盘绝对路径，必须在 Python 的 DATA_ROOT 内（否则 /api/ingest 会 400） */
    private String storedPath;

    /** PDF 页数；docx 无页码时为 null */
    private Integer pageCount;

    private Integer chunkCount;

    /** pending / queued / ingesting / ready / failed / quarantined */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
