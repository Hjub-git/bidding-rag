-- =====================================================================
-- 02_rag_tables.sql
-- RAG 相关的两张表。可重复执行（CREATE TABLE IF NOT EXISTS）。
--
-- rag_qa_log        —— 问答审计（断连 aborted / trace_id 可查）
-- tender_documents  —— 上传文档元数据（上传后 status=ready）
--
-- 注意：MySQL 8 **不支持** ADD COLUMN IF NOT EXISTS（那是 MariaDB 语法），
-- 所以这里只用 CREATE TABLE IF NOT EXISTS；后续加列要像 03_add_confidential.sql
-- 那样用 information_schema 探测 + PREPARE。
-- =====================================================================

-- ─── 问答审计 ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS rag_qa_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  trace_id    CHAR(32)     NOT NULL COMMENT 'Java 生成，贯穿 HTTP 头 / Java 日志 / Python 日志 / 本表',
  tender_id   BIGINT       NOT NULL COMMENT '提问所属项目（隔离键）',
  user_id     BIGINT       NOT NULL COMMENT '提问人',
  query_text  TEXT         NOT NULL COMMENT '用户原问题（叫 query_text 以避开 query 关键字的歧义）',
  hit_count   INT          NOT NULL DEFAULT 0 COMMENT '本项目命中 chunk 数；0 表示拒答',
  sources     JSON         NULL COMMENT '命中的 source/page 列表，供事后复核',
  answer_len  INT          NOT NULL DEFAULT 0 COMMENT '答案字符数',
  latency_ms  INT          NOT NULL DEFAULT 0 COMMENT '首帧到 done 的耗时',
  refused     TINYINT      NOT NULL DEFAULT 0 COMMENT '1=拒答（本项目未收录），0=正常作答',
  aborted     TINYINT      NOT NULL DEFAULT 0 COMMENT '1=客户端断连，与 refused 正交',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_trace (trace_id),
  KEY idx_tender_created (tender_id, created_at),
  KEY idx_refused (refused)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 问答审计';

-- ─── 上传文档元数据 ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tender_documents (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tender_id   BIGINT       NOT NULL COMMENT '所属招标项目',
  doc_id      BIGINT       NOT NULL COMMENT '文档 id（Python 侧 chunk 的 doc_id，同一个）',
  file_name   VARCHAR(255) NOT NULL COMMENT '原始文件名（仅展示用，不做边界）',
  stored_path VARCHAR(500) NOT NULL COMMENT '落盘绝对路径，必须在 Python 的 DATA_ROOT 内',
  page_count  INT          NULL COMMENT 'PDF 页数；docx 无页码时为 NULL',
  chunk_count INT          NOT NULL DEFAULT 0 COMMENT '入库后的 chunk 数',
  status      VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/queued/ingesting/ready/failed/quarantined',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tender_doc (tender_id, doc_id) COMMENT '幂等键：同一项目同一 doc_id 只能有一行',
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='招标文件上传记录';
