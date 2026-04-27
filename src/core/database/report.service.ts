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
      diffLines: number;
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
           total_code_lines, diff_lines, analyzed_lines, ai_contributed_lines, ai_contribution_ratio,
           skipped_lines, skipped_file_count,
           strict_matches, fuzzy_matches, deep_refactor_matches, no_matches,
           elapsed_ms)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
           total_code_lines = VALUES(total_code_lines),
           diff_lines = VALUES(diff_lines),
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
          summary.diffLines,
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

  // ═══════════════════════════════════════════════════════
  // Query APIs — Read-only methods for report retrieval
  // ═══════════════════════════════════════════════════════

  /**
   * Query filter options shared across list and stats APIs.
   */
  private buildWhereClause(filters: {
    userId?: string;
    repoName?: string;
    sysCode?: string;
    startDate?: string;
    endDate?: string;
  }): { where: string; params: any[] } {
    const conditions: string[] = [];
    const params: any[] = [];

    if (filters.userId) {
      conditions.push('user_id = ?');
      params.push(filters.userId);
    }
    if (filters.repoName) {
      conditions.push('repo_name LIKE ?');
      params.push(`%${filters.repoName}%`);
    }
    if (filters.sysCode) {
      conditions.push('sys_code = ?');
      params.push(filters.sysCode);
    }
    if (filters.startDate) {
      conditions.push('created_at >= ?');
      params.push(filters.startDate);
    }
    if (filters.endDate) {
      conditions.push('created_at <= ?');
      params.push(filters.endDate);
    }

    const where = conditions.length > 0
      ? 'WHERE ' + conditions.join(' AND ')
      : '';

    return { where, params };
  }

  /**
   * Get paginated list of attribution reports with optional filters.
   */
  async getReports(options: {
    page?: number;
    pageSize?: number;
    userId?: string;
    repoName?: string;
    sysCode?: string;
    startDate?: string;
    endDate?: string;
    sortBy?: string;
    sortOrder?: string;
  }): Promise<{
    data: any[];
    pagination: { page: number; pageSize: number; total: number; totalPages: number };
  }> {
    const page = Math.max(1, options.page ?? 1);
    const pageSize = Math.min(100, Math.max(1, options.pageSize ?? 20));
    const offset = (page - 1) * pageSize;

    // Whitelist sortable columns to prevent SQL injection
    const allowedSortColumns: Record<string, string> = {
      created_at: 'created_at',
      ai_contribution_ratio: 'ai_contribution_ratio',
      analyzed_lines: 'analyzed_lines',
    };
    const sortBy = allowedSortColumns[options.sortBy ?? ''] ?? 'created_at';
    const sortOrder = options.sortOrder === 'asc' ? 'ASC' : 'DESC';

    const { where, params } = this.buildWhereClause(options);

    // Count total
    const [countRows] = await this.pool.execute<any[]>(
      `SELECT COUNT(*) AS total FROM attribution_reports ${where}`,
      params,
    );
    const total = countRows[0]?.total ?? 0;

    // Fetch page
    const [rows] = await this.pool.execute<any[]>(
      `SELECT
         id, merge_id, repo_name, user_id, sys_code, title,
         total_code_lines, diff_lines, analyzed_lines,
         ai_contributed_lines, ai_contribution_ratio,
         skipped_lines, skipped_file_count,
         strict_matches, fuzzy_matches, deep_refactor_matches, no_matches,
         elapsed_ms, created_at
       FROM attribution_reports
       ${where}
       ORDER BY ${sortBy} ${sortOrder}
       LIMIT ? OFFSET ?`,
      [...params, pageSize, offset],
    );

    const data = rows.map(this.mapReportRow);

    return {
      data,
      pagination: {
        page,
        pageSize,
        total,
        totalPages: Math.ceil(total / pageSize),
      },
    };
  }

  /**
   * Get a single report by merge ID, including chunk details and message breakdown.
   */
  async getReportByMergeId(mergeId: string): Promise<{
    report: any;
    chunkDetails: any[];
    messageBreakdown: any[];
  } | null> {
    // Fetch report
    const [reportRows] = await this.pool.execute<any[]>(
      `SELECT
         id, merge_id, repo_name, user_id, sys_code, title,
         total_code_lines, diff_lines, analyzed_lines,
         ai_contributed_lines, ai_contribution_ratio,
         skipped_lines, skipped_file_count,
         strict_matches, fuzzy_matches, deep_refactor_matches, no_matches,
         elapsed_ms, created_at
       FROM attribution_reports
       WHERE merge_id = ?`,
      [mergeId],
    );

    if (reportRows.length === 0) return null;

    const report = this.mapReportRow(reportRows[0]);

    // Fetch chunk details
    const [chunkRows] = await this.pool.execute<any[]>(
      `SELECT
         id, file_path, start_line, end_line, total_lines,
         attribution, contributed_lines, matched_message_id,
         score, match_type, level
       FROM attribution_chunk_details
       WHERE report_id = ?
       ORDER BY file_path, start_line`,
      [report.id],
    );

    const chunkDetails = chunkRows.map((row: any) => ({
      id: row.id,
      filePath: row.file_path,
      startLine: row.start_line,
      endLine: row.end_line,
      totalLines: row.total_lines,
      attribution: row.attribution,
      contributedLines: Number(row.contributed_lines),
      matchedMessageId: row.matched_message_id,
      score: Number(row.score),
      matchType: row.match_type,
      level: row.level,
    }));

    // Aggregate message breakdown
    const [msgRows] = await this.pool.execute<any[]>(
      `SELECT
         matched_message_id AS messageId,
         SUM(contributed_lines) AS contributedLines,
         COUNT(*) AS chunkCount,
         GROUP_CONCAT(DISTINCT attribution) AS matchTypes
       FROM attribution_chunk_details
       WHERE report_id = ?
         AND matched_message_id IS NOT NULL
         AND attribution != 'none'
       GROUP BY matched_message_id`,
      [report.id],
    );

    const messageBreakdown = msgRows.map((row: any) => ({
      messageId: row.messageId,
      contributedLines: Number(row.contributedLines),
      chunkCount: row.chunkCount,
      matchTypes: row.matchTypes ? row.matchTypes.split(',') : [],
    }));

    return { report, chunkDetails, messageBreakdown };
  }

  /**
   * Get aggregated statistics across reports (with optional filters).
   */
  async getStatsSummary(filters: {
    userId?: string;
    repoName?: string;
    sysCode?: string;
    startDate?: string;
    endDate?: string;
  }): Promise<{
    totalReports: number;
    totalAnalyzedLines: number;
    totalAiContributedLines: number;
    avgAiContributionRatio: number;
    matchDistribution: {
      strict: number;
      fuzzy: number;
      deepRefactor: number;
      none: number;
    };
  }> {
    const { where, params } = this.buildWhereClause(filters);

    const [rows] = await this.pool.execute<any[]>(
      `SELECT
         COUNT(*) AS totalReports,
         COALESCE(SUM(analyzed_lines), 0) AS totalAnalyzedLines,
         COALESCE(SUM(ai_contributed_lines), 0) AS totalAiContributedLines,
         COALESCE(AVG(ai_contribution_ratio), 0) AS avgAiContributionRatio,
         COALESCE(SUM(strict_matches), 0) AS strictMatches,
         COALESCE(SUM(fuzzy_matches), 0) AS fuzzyMatches,
         COALESCE(SUM(deep_refactor_matches), 0) AS deepRefactorMatches,
         COALESCE(SUM(no_matches), 0) AS noMatches
       FROM attribution_reports
       ${where}`,
      params,
    );

    const row = rows[0] ?? {};
    return {
      totalReports: Number(row.totalReports) || 0,
      totalAnalyzedLines: Number(row.totalAnalyzedLines) || 0,
      totalAiContributedLines: Number(row.totalAiContributedLines) || 0,
      avgAiContributionRatio: Math.round((Number(row.avgAiContributionRatio) || 0) * 10000) / 10000,
      matchDistribution: {
        strict: Number(row.strictMatches) || 0,
        fuzzy: Number(row.fuzzyMatches) || 0,
        deepRefactor: Number(row.deepRefactorMatches) || 0,
        none: Number(row.noMatches) || 0,
      },
    };
  }

  /**
   * Map a raw DB row to a camelCase report object.
   */
  private mapReportRow(row: any) {
    return {
      id: row.id,
      mergeId: row.merge_id,
      repoName: row.repo_name,
      userId: row.user_id,
      sysCode: row.sys_code,
      title: row.title,
      totalCodeLines: row.total_code_lines,
      diffLines: row.diff_lines,
      analyzedLines: row.analyzed_lines,
      aiContributedLines: Number(row.ai_contributed_lines),
      aiContributionRatio: Number(row.ai_contribution_ratio),
      skippedLines: row.skipped_lines,
      skippedFileCount: row.skipped_file_count,
      strictMatches: row.strict_matches,
      fuzzyMatches: row.fuzzy_matches,
      deepRefactorMatches: row.deep_refactor_matches,
      noMatches: row.no_matches,
      elapsedMs: row.elapsed_ms,
      createdAt: row.created_at,
    };
  }
}
