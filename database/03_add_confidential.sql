-- =====================================================================
-- 03_add_confidential.sql
-- 目的：给 tender_projects 增加保密期字段（P0 必需，不是 P2 可选项）
--   不建它，RagController#assertCanAsk 的 manufacturer 分支就没有字段可查，
--   "保密期内不可提问"这条规则只能写在文档里、落不到代码上。
--
-- 语义：NULL = 不限制。判定用
--   confidential_until IS NOT NULL AND confidential_until > NOW()
-- 默认 NULL，存量数据无需回填，不影响现有功能。
--
-- 可重复执行：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS（那是 MariaDB 语法），
-- 所以用 information_schema 先探测再动态执行。
-- =====================================================================

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'tender_projects'
    AND COLUMN_NAME = 'confidential_until'
);

SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE tender_projects
     ADD COLUMN confidential_until DATETIME NULL
     COMMENT ''保密期截止时间；NULL=不限制。now() < 该值时为保密期''',
  'SELECT ''[skip] tender_projects.confidential_until 已存在'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
