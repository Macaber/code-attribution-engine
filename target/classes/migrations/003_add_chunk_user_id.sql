-- ============================================================
-- Code Attribution Engine — Migration 003
-- Add user_id column to attribution_chunk_details
-- ============================================================

ALTER TABLE attribution_chunk_details
  ADD COLUMN user_id VARCHAR(128) COMMENT '代码行作者 (从 diff 行前缀提取)' AFTER report_id;

-- Add index for per-user statistics queries
CREATE INDEX idx_user_id ON attribution_chunk_details (user_id);
