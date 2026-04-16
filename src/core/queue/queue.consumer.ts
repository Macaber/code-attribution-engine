import { Job, Worker } from 'bullmq';
import { AttributionWorker } from '../../domains/attribution/attribution.worker';
import { AttributionJobData, MatchResult } from '../../types';
import { QUEUE_NAME, getRedisConnection } from './queue.config';

/**
 * QueueConsumer — BullMQ Worker that dequeues and processes attribution jobs.
 *
 * Delegates actual analysis to AttributionWorker, handles errors and logging.
 */
export class QueueConsumer {
  private readonly worker: Worker<AttributionJobData>;
  private readonly attributionWorker: AttributionWorker;

  constructor() {
    this.attributionWorker = new AttributionWorker();

    this.worker = new Worker<AttributionJobData>(
      QUEUE_NAME,
      async (job: Job<AttributionJobData>) => {
        return this.processJob(job);
      },
      {
        connection: getRedisConnection(),
        concurrency: parseInt(process.env.WORKER_CONCURRENCY ?? '2', 10),
        limiter: {
          max: 10,
          duration: 60_000, // max 10 jobs per minute
        },
      },
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
      const summary = AttributionWorker.summarize(results);
      const elapsed = Date.now() - startTime;

      console.log(
        `[QueueConsumer] Job ${job.id} completed in ${elapsed}ms — ` +
        `${summary.totalLines} lines analyzed, ` +
        `${summary.aiContributedLines} AI-contributed (${(summary.aiContributionRatio * 100).toFixed(1)}%), ` +
        `strict: ${summary.strictMatches}, fuzzy: ${summary.fuzzyMatches}, ` +
        `deep_refactor: ${summary.deepRefactorMatches}, none: ${summary.noMatches}`,
      );

      // TODO: Persist results to database via report service
      return results;
    } catch (error) {
      const elapsed = Date.now() - startTime;
      console.error(
        `[QueueConsumer] Job ${job.id} failed after ${elapsed}ms:`,
        error,
      );
      throw error; // BullMQ will handle retry
    }
  }

  /**
   * Set up BullMQ worker event handlers for monitoring.
   */
  private setupEventHandlers() {
    this.worker.on('completed', (job) => {
      console.log(`[QueueConsumer] ✅ Job ${job.id} completed successfully`);
    });

    this.worker.on('failed', (job, error) => {
      console.error(
        `[QueueConsumer] ❌ Job ${job?.id} failed:`,
        error.message,
      );
    });

    this.worker.on('error', (error) => {
      console.error('[QueueConsumer] Worker error:', error);
    });
  }

  /**
   * Gracefully shut down the worker.
   */
  async close() {
    await this.worker.close();
  }
}
