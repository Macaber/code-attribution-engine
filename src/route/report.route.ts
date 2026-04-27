import { Router, Request, Response } from 'express';
import { ReportService } from '../core/database/report.service';

/**
 * ReportRoute — Express router for querying attribution reports.
 *
 * GET /api/reports              — Paginated list with filters
 * GET /api/reports/stats/summary — Aggregated statistics
 * GET /api/reports/:mergeId     — Single report detail
 */
export function createReportRouter(reportService: ReportService): Router {
  const router = Router();

  /**
   * GET /api/reports/stats/summary
   *
   * Returns aggregated statistics across all reports.
   * Supports the same filters as the list endpoint.
   *
   * NOTE: This route must be defined BEFORE /:mergeId to avoid
   * Express matching "stats" as a mergeId parameter.
   */
  router.get('/stats/summary', async (req: Request, res: Response) => {
    try {
      const stats = await reportService.getStatsSummary({
        userId: req.query.userId as string | undefined,
        repoName: req.query.repoName as string | undefined,
        sysCode: req.query.sysCode as string | undefined,
        startDate: req.query.startDate as string | undefined,
        endDate: req.query.endDate as string | undefined,
      });

      res.json(stats);
    } catch (error) {
      console.error('[ReportRoute] stats/summary error:', error);
      res.status(500).json({ error: 'Internal server error' });
    }
  });

  /**
   * GET /api/reports
   *
   * Query parameters:
   * - page (default: 1)
   * - pageSize (default: 20, max: 100)
   * - userId, repoName, sysCode — filters
   * - startDate, endDate — date range (ISO format)
   * - sortBy: created_at | ai_contribution_ratio | analyzed_lines
   * - sortOrder: desc (default) | asc
   */
  router.get('/', async (req: Request, res: Response) => {
    try {
      const result = await reportService.getReports({
        page: req.query.page ? parseInt(req.query.page as string, 10) : undefined,
        pageSize: req.query.pageSize ? parseInt(req.query.pageSize as string, 10) : undefined,
        userId: req.query.userId as string | undefined,
        repoName: req.query.repoName as string | undefined,
        sysCode: req.query.sysCode as string | undefined,
        startDate: req.query.startDate as string | undefined,
        endDate: req.query.endDate as string | undefined,
        sortBy: req.query.sortBy as string | undefined,
        sortOrder: req.query.sortOrder as string | undefined,
      });

      res.json(result);
    } catch (error) {
      console.error('[ReportRoute] list error:', error);
      res.status(500).json({ error: 'Internal server error' });
    }
  });

  /**
   * GET /api/reports/:mergeId
   *
   * Returns a single report with:
   * - report: the main attribution_reports row
   * - chunkDetails: all attribution_chunk_details rows
   * - messageBreakdown: per AI message contribution aggregation
   */
  router.get('/:mergeId', async (req: Request, res: Response) => {
    try {
      const rawParam = req.params.mergeId;
      const mergeId = Array.isArray(rawParam) ? rawParam[0] : rawParam;

      if (!mergeId) {
        res.status(400).json({ error: 'Missing mergeId parameter' });
        return;
      }

      const result = await reportService.getReportByMergeId(mergeId);

      if (!result) {
        res.status(404).json({ error: `Report not found for mergeId: ${mergeId}` });
        return;
      }

      res.json(result);
    } catch (error) {
      console.error('[ReportRoute] detail error:', error);
      res.status(500).json({ error: 'Internal server error' });
    }
  });

  return router;
}
