import { Queue } from 'bullmq';
import { AttributionJobData } from '../../types';
import { QUEUE_NAME, getRedisConnection, QUEUE_OPTIONS } from './queue.config';

/**
 * QueueProducer — Pushes attribution analysis jobs to BullMQ.
 */
export class QueueProducer {
  private readonly queue: Queue<AttributionJobData>;

  constructor() {
    this.queue = new Queue<AttributionJobData>(QUEUE_NAME, {
      connection: getRedisConnection(),
      ...QUEUE_OPTIONS,
    });
  }

  /**
   * Add a new attribution analysis job to the queue.
   *
   * @param data - Job data containing diff and AI messages
   * @returns The created BullMQ Job
   */
  async addJob(data: AttributionJobData) {
    const jobId = `${data.mergeId}-${Date.now()}`;
    const job = await this.queue.add('analyze-merge', data, {
      jobId,
    });
    console.log(
      `[QueueProducer] Job ${job.id} added for merge ${data.mergeId} (repo: ${data.repoName})`,
    );
    return job;
  }

  /**
   * Gracefully close the queue connection.
   */
  async close() {
    await this.queue.close();
  }
}
