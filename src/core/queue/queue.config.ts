import { ConnectionOptions } from 'bullmq';

/**
 * Queue configuration — Redis connection and queue naming.
 */
export const QUEUE_NAME = 'attribution-analysis';

export function getRedisConnection(): ConnectionOptions {
  return {
    host: process.env.REDIS_HOST ?? 'localhost',
    port: parseInt(process.env.REDIS_PORT ?? '6379', 10),
    password: process.env.REDIS_PASSWORD ?? undefined,
    maxRetriesPerRequest: null,
  };
}

export const QUEUE_OPTIONS = {
  defaultJobOptions: {
    attempts: 3,
    backoff: {
      type: 'exponential' as const,
      delay: 2000,
    },
    removeOnComplete: {
      count: 1000,
      age: 24 * 3600, // 24 hours
    },
    removeOnFail: {
      count: 5000,
    },
  },
};
