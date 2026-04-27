import express, { Application, Request, Response } from 'express';
import { createWebhookRouter } from './route/webhook.route';
import { createReportRouter } from './route/report.route';
import { QueueProducer } from './core/queue/queue.producer';
import { ReportService } from './core/database/report.service';

/**
 * Create and configure the Express application.
 *
 * @param reportService - Optional ReportService for query APIs (null if DB unavailable)
 */
export function createApp(reportService?: ReportService): { app: Application; queueProducer: QueueProducer } {
  const app = express();

  // ─── Middleware ────────────────────────────────────────
  app.use(express.json({ limit: '10mb' })); // Large diffs can be big
  app.use(express.urlencoded({ extended: true }));

  // ─── Health Check ─────────────────────────────────────
  app.get('/health', (_req: Request, res: Response) => {
    res.json({
      status: 'ok',
      service: 'code-attribution-engine',
      timestamp: new Date().toISOString(),
    });
  });

  // ─── Coding Webhook Routes ─────────────────────────────
  const queueProducer = new QueueProducer();
  app.use('/api/coding', createWebhookRouter(queueProducer));

  // ─── Report Query Routes ───────────────────────────────
  if (reportService) {
    app.use('/api/reports', createReportRouter(reportService));
    console.log('[Server] Report query APIs registered at /api/reports');
  }

  // ─── 404 Handler ──────────────────────────────────────
  app.use((_req: Request, res: Response) => {
    res.status(404).json({ error: 'Not Found' });
  });

  return { app, queueProducer };
}
