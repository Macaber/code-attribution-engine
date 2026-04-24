-- ============================================================
-- Code Attribution Engine — MySQL Schema
-- Version: 001
-- ============================================================

-- 1. 归因报告主表（成功任务）
CREATE TABLE IF NOT EXISTS attribution_reports (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  merge_id              VARCHAR(128) NOT NULL COMMENT 'Merge request ID (业务唯一键)',
  repo_name             VARCHAR(256) NOT NULL COMMENT '仓库名',
  user_id               VARCHAR(128) NOT NULL COMMENT '操作员账号 (oa)',
  sys_code              VARCHAR(64)  COMMENT '系统代码',
  title                 VARCHAR(512) COMMENT 'Merge 标题',
  total_code_lines      INT NOT NULL DEFAULT 0 COMMENT '全部文件总行数',
  diff_lines            INT NOT NULL DEFAULT 0 COMMENT 'Diff 原始行数 (含空行)',
  analyzed_lines        INT NOT NULL DEFAULT 0 COMMENT '实际分析的非空行数 (去除空行)',
  ai_contributed_lines  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT 'AI 贡献行数 (加权)',
  ai_contribution_ratio DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT 'AI 贡献率 = ai_contributed / analyzed',
  skipped_lines         INT NOT NULL DEFAULT 0 COMMENT '跳过的行数 (无 diff 的文件)',
  skipped_file_count    INT NOT NULL DEFAULT 0 COMMENT '跳过的文件数',
  strict_matches        INT NOT NULL DEFAULT 0 COMMENT 'STRICT 匹配的 chunk 数',
  fuzzy_matches         INT NOT NULL DEFAULT 0 COMMENT 'FUZZY 匹配的 chunk 数',
  deep_refactor_matches INT NOT NULL DEFAULT 0 COMMENT 'DEEP_REFACTOR 匹配的 chunk 数',
  no_matches            INT NOT NULL DEFAULT 0 COMMENT '无匹配的 chunk 数',
  elapsed_ms            INT NOT NULL DEFAULT 0 COMMENT '处理耗时 (毫秒)',
  created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_merge_id (merge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. 归因溯源明细表（per-chunk，支持按文件、按 message 维度钻取）
CREATE TABLE IF NOT EXISTS attribution_chunk_details (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_id           BIGINT NOT NULL COMMENT '关联的报告 ID',
  file_path           VARCHAR(512) NOT NULL COMMENT '文件路径',
  start_line          INT NOT NULL COMMENT 'Diff 起始行',
  end_line            INT NOT NULL COMMENT 'Diff 结束行',
  total_lines         INT NOT NULL COMMENT '本 chunk 总行数',
  attribution         VARCHAR(32) NOT NULL COMMENT '归因类型: strict/fuzzy/deep_refactor/none',
  contributed_lines   DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT 'AI 贡献行数',
  matched_message_id  VARCHAR(128) COMMENT '匹配到的 AI 消息 ID (溯源)',
  score               DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '相似度分数',
  match_type          VARCHAR(32) NOT NULL COMMENT '匹配类型: STRICT/FUZZY/DEEP_REFACTOR/NONE',
  level               VARCHAR(16) NOT NULL COMMENT '管线层级: L1/L2/L3/FAILED_ALL',
  CONSTRAINT fk_chunk_report FOREIGN KEY (report_id) REFERENCES attribution_reports(id) ON DELETE CASCADE,
  INDEX idx_report_id (report_id),
  INDEX idx_message_id (matched_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. 失败任务表（支持后续重试）
CREATE TABLE IF NOT EXISTS attribution_failed_jobs (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id          VARCHAR(128) COMMENT 'BullMQ Job ID',
  merge_id        VARCHAR(128) NOT NULL COMMENT 'Merge request ID',
  repo_name       VARCHAR(256) COMMENT '仓库名',
  user_id         VARCHAR(128) COMMENT '操作员账号',
  job_data        JSON NOT NULL COMMENT '完整的任务数据 (可用于重新入队)',
  error_message   TEXT COMMENT '错误信息',
  error_stack     TEXT COMMENT '错误堆栈',
  attempt_count   INT NOT NULL DEFAULT 1 COMMENT '已尝试次数',
  status          ENUM('pending', 'retrying', 'resolved', 'abandoned') DEFAULT 'pending' COMMENT '状态',
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_status (status),
  INDEX idx_merge_id (merge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 常用查询视图：按 AI 消息聚合贡献度 (messageBreakdown)
-- ============================================================
-- SELECT
--   d.matched_message_id AS messageId,
--   SUM(d.contributed_lines) AS contributedLines,
--   COUNT(*) AS chunkCount,
--   GROUP_CONCAT(DISTINCT d.attribution) AS matchTypes
-- FROM attribution_chunk_details d
-- JOIN attribution_reports r ON r.id = d.report_id
-- WHERE r.merge_id = ?
--   AND d.matched_message_id IS NOT NULL
--   AND d.attribution != 'none'
-- GROUP BY d.matched_message_id;
