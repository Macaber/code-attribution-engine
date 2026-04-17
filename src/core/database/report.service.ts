import { Pool, ResultSetHeader } from 'mysql2/promise';
import { AttributionJobData } from '../../types';

/**
 * ReportService — Persists attribution results and failed jobs to MySQL.
 *
 * - saveReport(): Transactionally writes report + chunk details
 * - saveFailedJob(): Records failed jobs for later retry
 * - getRetryableJobs(): Queries pending failed jobs
 * - markJobRetrying() / markJobResolved(): State transitions
 */
export class ReportService {
  constructor(private readonly pool: Pool) {}

  /**
   * Persist a successful attribution result to MySQL.
   * Uses a transaction to ensure report + chunk details are atomic.
   */
  async saveReport(
    jobData: AttributionJobData,
    summary: {
      totalCodeLines: number;
      analyzedLines: number;
      aiContributedLines: number;
      aiContributionRatio: number;
      skippedLines: number;
      skippedFileCount: number;
      strictMatches: number;
      fuzzyMatches: number;
      deepRefactorMatches: number;
      noMatches: number;
      chunkDetails: Array<{
        filePath: string;
        startLine: number;
        endLine: number;
        totalLines: number;
        attribution: string;
        contributedLines: number;
        matchedMessageId: string | null;
        score: number;
        matchType: string;
        level: string;
      }>;
    },
    elapsedMs: number,
  ): Promise<number> {
    const conn = await this.pool.getConnection();

    try {
      await conn.beginTransaction();

      // ── Insert report (upsert on merge_id) ──
      const [reportResult] = await conn.execute<ResultSetHeader>(
        `INSERT INTO attribution_reports
          (merge_id, repo_name, user_id, sys_code, title,
           total_code_lines, analyzed_lines, ai_contributed_lines, ai_contribution_ratio,
           skipped_lines, skipped_file_count,
           strict_matches, fuzzy_matches, deep_refactor_matches, no_matches,
           elapsed_ms)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
           total_code_lines = VALUES(total_code_lines),
           analyzed_lines = VALUES(analyzed_lines),
           ai_contributed_lines = VALUES(ai_contributed_lines),
           ai_contribution_ratio = VALUES(ai_contribution_ratio),
           skipped_lines = VALUES(skipped_lines),
           skipped_file_count = VALUES(skipped_file_count),
           strict_matches = VALUES(strict_matches),
           fuzzy_matches = VALUES(fuzzy_matches),
           deep_refactor_matches = VALUES(deep_refactor_matches),
           no_matches = VALUES(no_matches),
           elapsed_ms = VALUES(elapsed_ms)`,
        [
          jobData.mergeId,
          jobData.repoName,
          jobData.userId,
          jobData.sysCode ?? null,
          jobData.title ?? null,
          summary.totalCodeLines,
          summary.analyzedLines,
          summary.aiContributedLines,
          summary.aiContributionRatio,
          summary.skippedLines,
          summary.skippedFileCount,
          summary.strictMatches,
          summary.fuzzyMatches,
          summary.deepRefactorMatches,
          summary.noMatches,
          elapsedMs,
        ],
      );

      // When ON DUPLICATE KEY UPDATE fires, insertId may be 0
      let reportId = reportResult.insertId;
      if (!reportId) {
        const [rows] = await conn.execute<any[]>(
          'SELECT id FROM attribution_reports WHERE merge_id = ?',
          [jobData.mergeId],
        );
        reportId = rows[0]?.id;
      }

      // ── Delete existing chunk details (for upsert scenario) ──
      await conn.execute(
        'DELETE FROM attribution_chunk_details WHERE report_id = ?',
        [reportId],
      );

      // ── Batch insert chunk details ──
      if (summary.chunkDetails.length > 0) {
        const placeholders: string[] = [];
        const values: any[] = [];

        for (const chunk of summary.chunkDetails) {
          placeholders.push('(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
          values.push(
            reportId,
            chunk.filePath,
            chunk.startLine,
            chunk.endLine,
            chunk.totalLines,
            chunk.attribution,
            chunk.contributedLines,
            chunk.matchedMessageId,
            chunk.score,
            chunk.matchType,
            chunk.level,
          );
        }

        await conn.execute(
          `INSERT INTO attribution_chunk_details
            (report_id, file_path, start_line, end_line, total_lines,
             attribution, contributed_lines, matched_message_id,
             score, match_type, level)
           VALUES ${placeholders.join(', ')}`,
          values,
        );
      }

      await conn.commit();
      console.log(
        `[ReportService] Saved report #${reportId} for merge ${jobData.mergeId} ` +
        `(${summary.chunkDetails.length} chunk details)`,
      );

      return reportId;
    } catch (error) {
      await conn.rollback();
      throw error;
    } finally {
      conn.release();
    }
  }

  /**
   * Record a failed job for later retry.
   * If the same merge_id already has a pending failure, increment attempt_count.
   */
  async saveFailedJob(
    jobId: string | undefined,
    jobData: AttributionJobData,
    error: Error,
  ): Promise<void> {
    try {
      await this.pool.execute(
        `INSERT INTO attribution_failed_jobs
          (job_id, merge_id, repo_name, user_id, job_data, error_message, error_stack, attempt_count, status)
         VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'pending')
         ON DUPLICATE KEY UPDATE
           error_message = VALUES(error_message),
           error_stack = VALUES(error_stack),
           attempt_count = attempt_count + 1,
           status = 'pending'`,
        [
          jobId ?? null,
          jobData.mergeId,
          jobData.repoName ?? null,
          jobData.userId ?? null,
          JSON.stringify(jobData),
          error.message ?? 'Unknown error',
          error.stack ?? null,
        ],
      );
      console.log(
        `[ReportService] Saved failed job for merge ${jobData.mergeId}`,
      );
    } catch (dbError) {
      // Don't let DB failure mask the original error
      console.error(
        '[ReportService] Failed to persist failed job:',
        (dbError as Error).message,
      );
    }
  }

  /**
   * Get all retryable failed jobs (status = 'pending').
   */
  async getRetryableJobs(): Promise<
    Array<{
      id: number;
      mergeId: string;
      jobData: AttributionJobData;
      attemptCount: number;
    }>
  > {
    const [rows] = await this.pool.execute<any[]>(
      `SELECT id, merge_id, job_data, attempt_count
       FROM attribution_failed_jobs
       WHERE status = 'pending'
       ORDER BY created_at ASC`,
    );

    return rows.map((row: any) => ({
      id: row.id,
      mergeId: row.merge_id,
      jobData: typeof row.job_data === 'string'
        ? JSON.parse(row.job_data)
        : row.job_data,
      attemptCount: row.attempt_count,
    }));
  }

  /**
   * Mark a failed job as "retrying".
   */
  async markJobRetrying(id: number): Promise<void> {
    await this.pool.execute(
      `UPDATE attribution_failed_jobs SET status = 'retrying' WHERE id = ?`,
      [id],
    );
  }

  /**
   * Mark a failed job as "resolved" (successfully retried).
   */
  async markJobResolved(id: number): Promise<void> {
    await this.pool.execute(
      `UPDATE attribution_failed_jobs SET status = 'resolved' WHERE id = ?`,
      [id],
    );
  }

  /**
   * Mark a failed job as "abandoned" (exceeded max retries).
   */
  async markJobAbandoned(id: number): Promise<void> {
    await this.pool.execute(
      `UPDATE attribution_failed_jobs SET status = 'abandoned' WHERE id = ?`,
      [id],
    );
  }
}
