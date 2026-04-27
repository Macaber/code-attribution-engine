import 'dotenv/config';
import { createApp } from './app';
import { QueueConsumer } from './core/queue/queue.consumer';
import { getPool, testConnection, closePool } from './core/database/database.config';
import { ReportService } from './core/database/report.service';

const PORT = parseInt(process.env.PORT ?? '3000', 10);

async function main() {
  console.log('═══════════════════════════════════════════════');
  console.log('  Code Attribution Engine');
  console.log('  AI Code Adoption Rate Analysis');
  console.log('═══════════════════════════════════════════════');

  // ─── Initialize MySQL Connection Pool ─────────────────
  let reportService: ReportService | undefined;
  try {
    const connected = await testConnection();
    if (connected) {
      reportService = new ReportService(getPool());
      console.log('[Database] ReportService initialized');
    }
  } catch (error) {
    console.warn(
      '[Database] Could not connect to MySQL:',
      (error as Error).message,
    );
    console.warn('[Database] The service will run without persistence.');
  }

  // ─── Start Express Server ─────────────────────────────
  const { app, queueProducer } = createApp(reportService);
  const server = app.listen(PORT, () => {
    console.log(`[Server] HTTP server listening on port ${PORT}`);
    console.log(`[Server] Health check: http://localhost:${PORT}/health`);
    console.log(`[Server] doMerge webhook: POST http://localhost:${PORT}/api/coding/doMerge`);
    console.log(`[Server] Report queries: GET http://localhost:${PORT}/api/reports`);
  });

  // ─── Start BullMQ Consumer ────────────────────────────
  let consumer: QueueConsumer | null = null;
  try {
    consumer = new QueueConsumer(reportService);
    console.log('[Worker] BullMQ attribution worker started');
  } catch (error) {
    console.warn(
      '[Worker] Could not start BullMQ worker (Redis may not be available):',
      (error as Error).message,
    );
    console.warn('[Worker] The HTTP server is still running without queue processing.');
  }

  // ─── Graceful Shutdown ────────────────────────────────
  const shutdown = async (signal: string) => {
    console.log(`\n[Shutdown] Received ${signal}, shutting down gracefully...`);

    server.close(() => {
      console.log('[Shutdown] HTTP server closed');
    });

    if (queueProducer) {
      await queueProducer.close();
      console.log('[Shutdown] Queue producer closed');
    }

    if (consumer) {
      await consumer.close();
      console.log('[Shutdown] BullMQ worker closed');
    }

    await closePool();

    process.exit(0);
  };

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

main().catch((error) => {
  console.error('[Fatal] Failed to start:', error);
  process.exit(1);
});
