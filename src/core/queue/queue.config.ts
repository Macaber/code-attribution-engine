import Bull from 'bull';

/**
 * Queue configuration — Redis connection and queue naming.
 */
export const QUEUE_NAME = 'attribution-analysis';

export function getRedisOptions(): Bull.QueueOptions['redis'] {
  return {
    host: process.env.REDIS_HOST ?? 'localhost',
    port: parseInt(process.env.REDIS_PORT ?? '6379', 10),
    password: process.env.REDIS_PASSWORD ?? undefined,
    // Bull handles retries differently, removing BullMQ specific maxRetriesPerRequest
  };
}

export const QUEUE_OPTIONS: Bull.QueueOptions = {
  redis: getRedisOptions(),
  limiter: {
    max: 10,
    duration: 60_000, // max 10 jobs per minute
  },
  defaultJobOptions: {
    attempts: 3,
    backoff: {
      type: 'exponential',
      delay: 2000,
    },
    removeOnComplete: 1000, // Keep last 1000 completed jobs
    removeOnFail: 5000,     // Keep last 5000 failed jobs
  },
};
