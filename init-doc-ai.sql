-- ============================================================
-- DocAI 业务库初始化脚本（汇总版）
-- 覆盖：user / file_metadata / document / document_version /
--       document_comment / document_annotation
-- 说明：
--   1. 项目根目录的 mysql-schema.sql 只建 Nacos 的 nacos_config 库，
--      并不会建业务库 doc_ai，也不会建下面这些表。
--   2. 原仓库里 user.sql / document.sql 不会被任何自动流程执行，
--      file-service 更是连 file_metadata 的建表脚本都没有（缺口已在此补齐）。
--   3. 本脚本可直接执行：在 MySQL 客户端里 SOURCE 本文件即可。
--   4. 默认账号：admin / admin123（ADMIN），user / user123（USER）。
--      密码为 BCrypt 密文，对应明文见上。如需修改请自行重新生成哈希。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `doc_ai`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `doc_ai`;

-- -------------------- 用户表（与 User 实体一致）--------------------
CREATE TABLE IF NOT EXISTS `user` (
  `id`         BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`   VARCHAR(50)  NOT NULL COMMENT '用户名',
  `password`   VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密）',
  `email`      VARCHAR(100) NOT NULL COMMENT '邮箱',
  `phone`      VARCHAR(20)  NOT NULL COMMENT '手机号',
  `role`       VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色',
  `status`     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '状态（0:禁用, 1:启用）',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- -------------------- 文件元数据表（补齐 file-service 缺口）--------------------
CREATE TABLE IF NOT EXISTS `file_metadata` (
  `id`               VARCHAR(64)  NOT NULL COMMENT '主键（UUID）',
  `file_id`          VARCHAR(64)  DEFAULT NULL COMMENT '业务文件ID',
  `file_name`        VARCHAR(255) DEFAULT NULL COMMENT '文件名',
  `original_file_name` VARCHAR(255) DEFAULT NULL COMMENT '原始文件名',
  `file_path`        VARCHAR(512) DEFAULT NULL COMMENT '存储路径',
  `file_type`        VARCHAR(100) DEFAULT NULL COMMENT '文件类型',
  `file_size`        BIGINT       DEFAULT NULL COMMENT '文件大小（字节）',
  `md5`              VARCHAR(64)  DEFAULT NULL COMMENT 'MD5 校验值',
  `storage_type`     VARCHAR(50)  DEFAULT NULL COMMENT '存储类型（minio/local）',
  `bucket_name`      VARCHAR(128) DEFAULT NULL COMMENT '存储桶',
  `object_key`       VARCHAR(512) DEFAULT NULL COMMENT '对象键',
  `status`           VARCHAR(20)  DEFAULT NULL COMMENT '状态',
  `create_by`        VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
  `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_file_id` (`file_id`),
  KEY `idx_status` (`status`),
  KEY `idx_file_name` (`file_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';

-- -------------------- 文档表 --------------------
CREATE TABLE IF NOT EXISTS `document` (
  `id`         VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '文档ID',
  `title`      VARCHAR(255) NOT NULL COMMENT '文档标题',
  `content`    TEXT COMMENT '文档内容',
  `summary`    TEXT COMMENT '文档摘要',
  `keywords`   TEXT COMMENT '关键词（JSON格式）',
  `file_id`    VARCHAR(64) COMMENT '关联文件ID',
  `user_id`    BIGINT COMMENT '创建用户ID',
  `status`     VARCHAR(20) DEFAULT 'active' COMMENT '状态：active-活跃，deleted-已删除',
  `version`    INT DEFAULT 1 COMMENT '版本号',
  `category`   VARCHAR(50) COMMENT '分类',
  `tags`       TEXT COMMENT '标签（JSON格式）',
  `created_by` VARCHAR(64) COMMENT '创建人',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_status` (`status`),
  INDEX `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档表';

-- -------------------- 文档版本表 --------------------
CREATE TABLE IF NOT EXISTS `document_version` (
  `id`             VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '版本ID',
  `document_id`    VARCHAR(64) NOT NULL COMMENT '所属文档ID',
  `version_number` INT NOT NULL COMMENT '版本号',
  `title`          VARCHAR(255) NOT NULL COMMENT '文档标题',
  `content`        TEXT COMMENT '文档内容',
  `summary`        TEXT COMMENT '文档摘要',
  `keywords`       TEXT COMMENT '关键词',
  `change_log`     VARCHAR(500) COMMENT '变更日志',
  `created_by`     VARCHAR(64) COMMENT '创建人',
  `create_time`    DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  INDEX `idx_document_id` (`document_id`),
  INDEX `idx_version_number` (`version_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档版本表';

-- -------------------- 文档评论表 --------------------
CREATE TABLE IF NOT EXISTS `document_comment` (
  `id`          VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '评论ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '所属文档ID',
  `user_id`     BIGINT COMMENT '评论用户ID',
  `content`     TEXT COMMENT '评论内容',
  `parent_id`   VARCHAR(64) COMMENT '父评论ID（回复）',
  `created_by`  VARCHAR(64) COMMENT '创建人',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `status`      VARCHAR(20) DEFAULT 'active' COMMENT '状态：active-活跃，deleted-已删除',
  INDEX `idx_document_id` (`document_id`),
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档评论表';

-- -------------------- 文档批注表 --------------------
CREATE TABLE IF NOT EXISTS `document_annotation` (
  `id`              VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '批注ID',
  `document_id`     VARCHAR(64) NOT NULL COMMENT '所属文档ID',
  `user_id`         BIGINT COMMENT '批注用户ID',
  `line_number`     INT COMMENT '批注所在行号',
  `start_offset`    INT COMMENT '批注起始位置',
  `end_offset`      INT COMMENT '批注结束位置',
  `annotation_type` VARCHAR(50) COMMENT '批注类型：highlight-高亮，comment-注释，note-笔记',
  `content`         TEXT COMMENT '批注内容',
  `color`           VARCHAR(20) COMMENT '批注颜色',
  `status`          VARCHAR(20) DEFAULT 'active' COMMENT '状态：active-活跃，deleted-已删除',
  `created_by`      VARCHAR(64) COMMENT '创建人',
  `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX `idx_document_id` (`document_id`),
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_line_number` (`line_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档批注表';

-- -------------------- 默认账号（明文：admin/admin123，user/user123）--------------------
INSERT INTO `user` (`username`, `password`, `email`, `phone`, `role`, `status`) VALUES
('admin', '$2b$10$vYzd32C.fyiV8HNftuYxIOBIh0bw57Qq8V664Bh1KM7Nkh64P9Ilm', 'admin@docai.local', '13800000000', 'ADMIN', 1),
('user',  '$2b$10$DXmQ0FSjAFyaTi0LlnIkYONs25zCUytnljkr.EWErP34sPTIZtjzO', 'user@docai.local',  '13800000001', 'USER',  1);
