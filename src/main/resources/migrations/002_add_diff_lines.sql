-- ============================================================
-- Code Attribution Engine — Migration 002
-- Add diff_lines column to attribution_reports
-- ============================================================

ALTER TABLE attribution_reports
  ADD COLUMN diff_lines INT NOT NULL DEFAULT 0 COMMENT 'Diff 原始行数 (含空行)'
  AFTER total_code_lines;
