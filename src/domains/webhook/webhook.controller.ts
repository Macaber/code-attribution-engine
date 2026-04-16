import { Router, Request, Response } from 'express';
import { DoMergePayload, MergeFileDetail, AttributionJobData } from '../../types';
import { QueueProducer } from '../../core/queue/queue.producer';

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

      // ─── Construct job data ────────────────────────────
      // TODO: Fetch AI messages from database for this user (body.oa)
      const jobData: AttributionJobData = {
        mergeId: body.mergeId,
        repoName: body.repoName,
        userId: body.oa,
        sysCode: body.sysCode,
        title: body.title,
        fileDetails,
        aiMessages: [], // placeholder: should come from DB lookup by userId
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
