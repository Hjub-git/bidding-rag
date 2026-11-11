-- =====================================================================
-- 机电设备在线招投标系统 —— 数据库结构（重设计版）
-- 设计要点：
--   1) 三范式：用户/厂家/招标单位 拆成三张表，通过 user_id 关联，避免字段冗余
--   2) 逻辑删除：tender_projects/bids/messages/replies 都用 deleted 标记，
--      删除 = UPDATE deleted=1，数据可追溯、误删可恢复
--   3) 唯一约束兜底：bids 上 (manufacturer_id, tender_project_id) 唯一，
--      数据库层保证“一个厂家对一个项目只能投一次”，防并发重复投标
--   4) 金额用 DECIMAL、日期用 DATE/DATETIME，而不是 DOUBLE/VARCHAR
--   5) 外键 + 索引：常用查询列建索引，外键保证引用完整性
-- 说明：本脚本会重建所有表，仅用于本地开发初始化。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS bidding_system
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE bidding_system;

-- 按依赖顺序先删后建（保证重设计生效）
DROP TABLE IF EXISTS replies;
DROP TABLE IF EXISTS messages;
DROP TABLE IF EXISTS bids;
DROP TABLE IF EXISTS tender_projects;
DROP TABLE IF EXISTS manufacturers;
DROP TABLE IF EXISTS tender_units;
DROP TABLE IF EXISTS users;

-- ---------------------------------------------------------------------
-- 用户表：三类角色统一存储，用 role 区分
-- ---------------------------------------------------------------------
CREATE TABLE users (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  username   VARCHAR(50)  NOT NULL COMMENT '登录名',
  password   VARCHAR(100) NOT NULL COMMENT 'bcrypt 加密后的密码，绝不存明文',
  email      VARCHAR(100) NOT NULL,
  role       VARCHAR(20)  NOT NULL COMMENT 'manufacturer/tender/admin',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- ---------------------------------------------------------------------
-- 厂家档案表
-- ---------------------------------------------------------------------
CREATE TABLE manufacturers (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  user_id          BIGINT       NOT NULL COMMENT '关联 users.id',
  company_name     VARCHAR(100) NOT NULL,
  legal_person     VARCHAR(50)  NOT NULL,
  contact_phone    VARCHAR(20)  NOT NULL,
  address          VARCHAR(255) NOT NULL,
  business_license VARCHAR(100) NOT NULL,
  company_profile  TEXT         NULL,
  status           VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'active/pending/rejected（管理员审核）',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_manufacturer_user (user_id),
  CONSTRAINT fk_manufacturer_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='机电设备生产厂家档案';

-- ---------------------------------------------------------------------
-- 招标单位档案表
-- ---------------------------------------------------------------------
CREATE TABLE tender_units (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  user_id          BIGINT       NOT NULL COMMENT '关联 users.id',
  company_name     VARCHAR(100) NOT NULL,
  legal_person     VARCHAR(50)  NOT NULL,
  contact_phone    VARCHAR(20)  NOT NULL,
  address          VARCHAR(255) NOT NULL,
  business_license VARCHAR(100) NOT NULL,
  company_profile  TEXT         NULL,
  status           VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'active/pending/rejected（管理员审核）',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tender_unit_user (user_id),
  CONSTRAINT fk_tender_unit_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='招标单位档案';

-- ---------------------------------------------------------------------
-- 招标项目表
-- ---------------------------------------------------------------------
CREATE TABLE tender_projects (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,
  project_number        VARCHAR(50)   NOT NULL COMMENT '项目编号（业务唯一）',
  tender_unit_id        BIGINT        NOT NULL COMMENT '发布方招标单位',
  equipment_name        VARCHAR(100)  NOT NULL,
  model                 VARCHAR(100)  NOT NULL,
  packaging_requirements TEXT         NULL,
  quantity              INT           NOT NULL,
  deadline              DATE          NOT NULL COMMENT '投标截止日期',
  deposit               DECIMAL(15,2) NOT NULL COMMENT '保证金，金额用 DECIMAL',
  description           TEXT          NULL,
  status                VARCHAR(20)   NOT NULL DEFAULT 'open' COMMENT 'open/closed/cancelled',
  deleted               TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
  created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 唯一约束把 deleted 一并纳入：逻辑删除后允许复用相同项目编号
  UNIQUE KEY uk_project_number (project_number, deleted),
  KEY idx_tender_status (status),
  KEY idx_tender_deadline (deadline),
  KEY idx_tender_unit (tender_unit_id),
  CONSTRAINT fk_tender_project_unit FOREIGN KEY (tender_unit_id) REFERENCES tender_units (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='招标项目';

-- ---------------------------------------------------------------------
-- 投标表
-- ---------------------------------------------------------------------
CREATE TABLE bids (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  tender_project_id   BIGINT        NOT NULL,
  manufacturer_id     BIGINT        NOT NULL,
  bid_date            DATE          NOT NULL,
  production_date     DATE          NOT NULL,
  warranty_period     VARCHAR(50)   NOT NULL,
  total_amount        DECIMAL(15,2) NOT NULL,
  deposit_payment_date DATE         NOT NULL,
  bid_document        VARCHAR(255)  NULL,
  status              VARCHAR(20)   NOT NULL DEFAULT 'submitted' COMMENT 'submitted/accepted/rejected',
  deleted             TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 核心：一个厂家对同一项目只能有一条“未删除”投标（数据库层兜底防重复/并发）；
  -- 把 deleted 纳入唯一索引，避免逻辑删除（撤回）后无法再次投标
  UNIQUE KEY uk_bid_manufacturer_project (manufacturer_id, tender_project_id, deleted),
  KEY idx_bid_project (tender_project_id),
  CONSTRAINT fk_bid_project FOREIGN KEY (tender_project_id) REFERENCES tender_projects (id),
  CONSTRAINT fk_bid_manufacturer FOREIGN KEY (manufacturer_id) REFERENCES manufacturers (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='投标';

-- ---------------------------------------------------------------------
-- 留言表
-- ---------------------------------------------------------------------
CREATE TABLE messages (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  user_id          BIGINT       NOT NULL,
  tender_project_id BIGINT      NULL COMMENT '可关联招标项目，可为空',
  title            VARCHAR(100) NOT NULL,
  content          TEXT         NOT NULL,
  is_public        TINYINT      NOT NULL DEFAULT 1 COMMENT '1公开 0私密',
  deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_message_user (user_id),
  CONSTRAINT fk_message_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_message_project FOREIGN KEY (tender_project_id) REFERENCES tender_projects (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='留言';

-- ---------------------------------------------------------------------
-- 回复表
-- ---------------------------------------------------------------------
CREATE TABLE replies (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  message_id BIGINT   NOT NULL,
  user_id    BIGINT   NOT NULL,
  content    TEXT     NOT NULL,
  deleted    TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_reply_message (message_id),
  CONSTRAINT fk_reply_message FOREIGN KEY (message_id) REFERENCES messages (id),
  CONSTRAINT fk_reply_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='留言回复';
