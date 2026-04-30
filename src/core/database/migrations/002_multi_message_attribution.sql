-- ============================================================
-- Code Attribution Engine — Incremental Migration
-- Version: 002 — Multi-message Attribution Support
-- ============================================================

-- Add matched_message_ids column to attribution_chunk_details
-- Stores comma-separated AI message IDs for multi-message attribution.
-- Example value: "msg_001,msg_002,msg_003"

ALTER TABLE attribution_chunk_details
  ADD COLUMN matched_message_ids TEXT COMMENT '所有贡献消息 ID (逗号分隔, 多消息归因)'
  AFTER matched_message_id;

-- Backfill: Copy existing single matched_message_id into matched_message_ids
UPDATE attribution_chunk_details
SET matched_message_ids = matched_message_id
WHERE matched_message_id IS NOT NULL
  AND (matched_message_ids IS NULL OR matched_message_ids = '');
