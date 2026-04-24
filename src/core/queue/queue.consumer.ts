import Queue, { Job } from 'bull';
import { AttributionWorker } from '../../domains/attribution/attribution.worker';
import { AttributionJobData, MatchResult } from '../../types';
import { QUEUE_NAME, QUEUE_OPTIONS } from './queue.config';
import { ReportService } from '../database/report.service';

/**
 * QueueConsumer — Bull Worker that dequeues and processes attribution jobs.
 *
 * Delegates actual analysis to AttributionWorker, persists results to MySQL,
 * and handles errors with failed-job recording.
 */
export class QueueConsumer {
  private readonly queue: Queue.Queue<AttributionJobData>;
  private readonly attributionWorker: AttributionWorker;
  private readonly reportService: ReportService | null;

  constructor(reportService?: ReportService) {
    this.attributionWorker = new AttributionWorker();
    this.reportService = reportService ?? null;

    this.queue = new Queue<AttributionJobData>(QUEUE_NAME, QUEUE_OPTIONS);

    // Set up processing for the 'analyze-merge' named job
    this.queue.process(
      'analyze-merge',
      parseInt(process.env.WORKER_CONCURRENCY ?? '2', 10),
      async (job: Job<AttributionJobData>) => {
        return this.processJob(job);
      }
    );

    this.setupEventHandlers();
  }

  /**
   * Process a single attribution job.
   */
  private async processJob(
    job: Job<AttributionJobData>,
  ): Promise<MatchResult[]> {
    const { mergeId, repoName, userId } = job.data;
    console.log(
      `[QueueConsumer] Processing job ${job.id} — merge: ${mergeId}, repo: ${repoName}, user: ${userId}`,
    );

    const startTime = Date.now();

    try {
      const results = await this.attributionWorker.process(job.data);
      const summary = AttributionWorker.summarize(results, job.data);
      const elapsed = Date.now() - startTime;

      console.log(
        `[QueueConsumer] Job ${job.id} completed in ${elapsed}ms — ` +
        `totalCodeLines: ${summary.totalCodeLines}, ` +
        `diffLines: ${summary.diffLines}, ` +
        `analyzedLines: ${summary.analyzedLines}, ` +
        `aiContributedLines: ${summary.aiContributedLines} (${(summary.aiContributionRatio * 100).toFixed(1)}%), ` +
        `skippedLines: ${summary.skippedLines} (${summary.skippedFileCount} files), ` +
        `strict: ${summary.strictMatches}, fuzzy: ${summary.fuzzyMatches}, ` +
        `deep_refactor: ${summary.deepRefactorMatches}, none: ${summary.noMatches}`,
      );

      // Log per-message traceability
      if (summary.messageBreakdown.length > 0) {
        console.log(`[QueueConsumer] Job ${job.id} AI message breakdown:`);
        for (const msg of summary.messageBreakdown) {
          console.log(
            `  - message: ${msg.messageId}, ` +
            `contributedLines: ${msg.contributedLines}, ` +
            `chunks: ${msg.chunkCount}, ` +
            `types: [${msg.matchTypes.join(', ')}]`,
          );
        }
      }

      // ── Persist results to MySQL ──
      if (this.reportService) {
        try {
          await this.reportService.saveReport(job.data, summary, elapsed);
        } catch (dbError) {
          console.error(
            `[QueueConsumer] Failed to persist report for job ${job.id}:`,
            (dbError as Error).message,
          );
          // Don't fail the job if DB write fails — results are still returned
        }
      }

      return results;
    } catch (error) {
      const elapsed = Date.now() - startTime;
      console.error(
        `[QueueConsumer] Job ${job.id} failed after ${elapsed}ms:`,
        error,
      );

      throw error; // Bull will handle retry
    }
  }

  /**
   * Set up Bull event handlers for monitoring.
   */
  private setupEventHandlers() {
    this.queue.on('completed', (job) => {
      console.log(`[QueueConsumer] ✅ Job ${job.id} completed successfully`);
    });

    this.queue.on('failed', async (job, error) => {
      console.error(
        `[QueueConsumer] ❌ Job ${job?.id} failed:`,
        error.message,
      );

      // Persist failed job to MySQL for later retry
      if (this.reportService && job) {
        await this.reportService.saveFailedJob(
          job.id.toString(), // Bull job.id can be string | number
          job.data,
          error,
        );
      }
    });

    this.queue.on('error', (error) => {
      console.error('[QueueConsumer] Queue error:', error);
    });
  }

  /**
   * Gracefully shut down the worker/queue.
   */
  async close() {
    await this.queue.close();
  }
}
