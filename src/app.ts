import express, { Application, Request, Response } from 'express';
import { createWebhookRouter } from './domains/webhook/webhook.controller';
import { QueueProducer } from './core/queue/queue.producer';

/**
 * Create and configure the Express application.
 */
export function createApp(): { app: Application; queueProducer: QueueProducer } {
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

  // ─── 404 Handler ──────────────────────────────────────
  app.use((_req: Request, res: Response) => {
    res.status(404).json({ error: 'Not Found' });
  });

  return { app, queueProducer };
}
