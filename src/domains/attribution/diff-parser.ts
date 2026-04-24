import parseDiff, { File, Change } from 'parse-diff';
import { DiffChunk } from '../../types';
import { Normalizer } from './normalizer';

/**
 * DiffParser — Extracts logical chunks of added lines from a unified Git diff.
 *
 * Uses the `parse-diff` library to parse, then groups contiguous added lines
 * into DiffChunk objects with normalized content.
 */
export class DiffParser {
  private readonly normalizer: Normalizer;

  constructor() {
    this.normalizer = new Normalizer();
  }

  /**
   * Parse a unified diff string and extract chunks of added code.
   *
   * Contiguous `+` (added) lines form a single chunk.
   * Any non-added line (context, deletion) breaks the chunk boundary.
   *
   * @param rawDiff - Standard unified diff string
   * @returns Array of DiffChunk objects
   */
  parse(rawDiff: string): DiffChunk[] {
    if (!rawDiff || rawDiff.trim().length === 0) return [];

    const files: File[] = parseDiff(rawDiff);
    const chunks: DiffChunk[] = [];

    for (const file of files) {
      const filePath = file.to ?? file.from ?? 'unknown';

      for (const chunk of file.chunks) {
        // Walk changes and group contiguous additions
        let currentLines: string[] = [];
        let startLine: number | null = null;
        let endLine: number = 0;

        for (const change of chunk.changes) {
          if (change.type === 'add') {
            if (startLine === null) {
              startLine = change.ln;
            }
            endLine = change.ln;
            currentLines.push(change.content.substring(1)); // Strip '+' prefix from parse-diff
          } else {
            // Non-add line breaks the contiguous block
            if (currentLines.length > 0 && startLine !== null) {
              chunks.push(this.buildChunk(filePath, startLine, endLine, currentLines));
              currentLines = [];
              startLine = null;
            }
          }
        }

        // Flush remaining lines
        if (currentLines.length > 0 && startLine !== null) {
          chunks.push(this.buildChunk(filePath, startLine, endLine, currentLines));
        }
      }
    }

    return chunks;
  }

  /**
   * Build a DiffChunk from collected lines.
   */
  private buildChunk(
    filePath: string,
    startLine: number,
    endLine: number,
    lines: string[],
  ): DiffChunk {
    const content = lines.join('\n');
    return {
      filePath,
      startLine,
      endLine,
      content,
      normalizedContent: this.normalizer.normalizeText(content),
    };
  }
}
