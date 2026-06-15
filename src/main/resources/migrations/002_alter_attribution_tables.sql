-- ============================================================
-- Code Attribution Engine — Incremental Migration
-- Version: 002
-- ============================================================

-- ── 1. 更新 attribution_reports 表 ──
-- 将现有为 NULL 的数据更新为默认值（空字符串），确保唯一索引能正确对齐


-- 修改唯一索引为复合索引
-- （在此保持 sys_code 为 NULL 允许以兼容某些分布式 DB 无法对已有数据表修改 NOT NULL 的限制，Java 逻辑层已做强校验，保证只写入非空字符串 ''）
ALTER TABLE attribution_reports DROP INDEX uk_merge_id;
ALTER TABLE attribution_reports ADD UNIQUE KEY uk_sys_code_merge_id (sys_code, merge_id);


-- ── 2. 更新 attribution_failed_jobs 表 ──
-- 新增 sys_code 字段（保持允许为 NULL，默认值为空字符串，规避分布式 DB 在已有数据表上加 NOT NULL 列的校验限制）
ALTER TABLE attribution_failed_jobs ADD COLUMN sys_code VARCHAR(64) NULL DEFAULT '' COMMENT '系统代码' AFTER user_id;

-- 将已有数据的该字段全部初始化为默认值并提交
UPDATE attribution_failed_jobs SET sys_code = '';
COMMIT;

ALTER TABLE attribution_failed_jobs DROP INDEX idx_merge_id;
ALTER TABLE attribution_failed_jobs ADD INDEX idx_sys_code_merge_id (sys_code, merge_id);


-- ── 3. 新建 attribution_file_details 表 ──
CREATE TABLE IF NOT EXISTS attribution_file_details (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_id   BIGINT NOT NULL COMMENT '关联的报告 ID',
  file_path   VARCHAR(512) NOT NULL COMMENT '文件路径',
  code        LONGTEXT COMMENT '完整代码',
  diff        LONGTEXT COMMENT 'Diff 内容',
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_file_report FOREIGN KEY (report_id) REFERENCES attribution_reports(id) ON DELETE CASCADE,
  INDEX idx_report_id (report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
