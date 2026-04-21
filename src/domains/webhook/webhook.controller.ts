import { Router, Request, Response } from 'express';
import { DoMergePayload, MergeFileDetail, AttributionJobData, AiMessage } from '../../types';
import { QueueProducer } from '../../core/queue/queue.producer';
import { getPool } from '../../core/database/database.config';
import { RowDataPacket } from 'mysql2';

/**
 * WebhookController — Express router for receiving CICD doMerge webhooks.
 *
 * POST /api/coding/doMerge
 * Receives merge data with embedded diffs, parses the detail field,
 * and pushes an attribution analysis job to BullMQ.
 */
export function createWebhookRouter(queueProducer: QueueProducer): Router {
  const router = Router();

  /**
   * POST /api/coding/doMerge
   *
   * Accepts a CICD merge payload containing:
   * - oa: operator account
   * - sysCode / sysName: system identifiers
   * - repoName: target repository
   * - mergeId: merge request ID
   * - title / createTime: merge metadata
   * - detail: JSON-stringified array of { path, code, diff }
   */
  router.post('/doMerge', async (req: Request, res: Response) => {
    try {
      const body = req.body as DoMergePayload;

      // ─── Validate required fields ──────────────────────
      if (!body.mergeId || !body.repoName || !body.detail) {
        res.status(400).json({
          error: 'Missing required fields: mergeId, repoName, detail',
        });
        return;
      }

      // ─── Parse detail JSON string ──────────────────────
      let fileDetails: MergeFileDetail[];
      try {
        fileDetails = JSON.parse(body.detail);
        if (!Array.isArray(fileDetails)) {
          throw new Error('detail is not an array');
        }
      } catch (parseError) {
        res.status(400).json({
          error: 'Invalid detail field: expected JSON array of {path, code, diff}',
        });
        return;
      }

      // Filter out entries without diffs
      fileDetails = fileDetails.filter(
        (f) => f.diff && f.diff.trim().length > 0,
      );

      if (fileDetails.length === 0) {
        res.status(200).json({
          status: 'skipped',
          mergeId: body.mergeId,
          message: 'No file diffs to analyze',
        });
        return;
      }

      console.log(
        `[Webhook] doMerge received — mergeId: ${body.mergeId}, repo: ${body.repoName}, ` +
        `files: ${fileDetails.length}, operator: ${body.oa}`,
      );

      // ─── Fetch AI messages from database for this user (body.oa) ────
      const aiMessages: AiMessage[] = [];
      try {
        const pool = getPool();
        // NOTE: Please adjust 'ai_messages' table name and column names if they differ in your schema.
        const [rows] = await pool.query<RowDataPacket[]>(
          `SELECT id, function_name, function_arguments, created_at 
           FROM ai_messages 
           WHERE user_oa = ? AND created_at >= DATE_SUB(NOW(), INTERVAL 1 MONTH)`,
          [body.oa]
        );

        for (const row of rows) {
          try {
            const args = JSON.parse(row.function_arguments);
            let rawContent = '';

            if (row.function_name === 'edit' && args.newString) {
              rawContent = args.newString;
            } else if (row.function_name === 'write' && args.content) {
              rawContent = args.content;
            }

            if (rawContent && rawContent.trim().length > 0) {
              aiMessages.push({
                messageId: String(row.id),
                userId: body.oa,
                timestamp: new Date(row.created_at),
                rawContent: rawContent
              });
            }
          } catch (parseErr) {
            console.warn(`[Webhook] Failed to parse ai_message arguments for id ${row.id}`);
          }
        }

        console.log(`[Webhook] Fetched ${aiMessages.length} valid AI messages for user ${body.oa}`);
      } catch (dbError) {
        console.error(`[Webhook] Failed to fetch AI messages from DB:`, dbError);
      }

      // ─── Construct job data ────────────────────────────
      const jobData: AttributionJobData = {
        mergeId: body.mergeId,
        repoName: body.repoName,
        userId: body.oa,
        sysCode: body.sysCode,
        title: body.title,
        fileDetails,
        aiMessages,
      };

      await queueProducer.addJob(jobData);

      res.status(202).json({
        status: 'accepted',
        mergeId: body.mergeId,
        repoName: body.repoName,
        filesCount: fileDetails.length,
        message: 'Attribution analysis job queued',
      });
    } catch (error) {
      console.error('[Webhook] doMerge error:', error);
      res.status(500).json({ error: 'Internal server error' });
    }
  });

  return router;
}
