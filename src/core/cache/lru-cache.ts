/**
 * LRUCache — Simple Least Recently Used cache with TTL expiry.
 *
 * Used to cache expensive computations like Tree-sitter AST parse results,
 * avoiding redundant parsing of the same file content within a job.
 */
export class LRUCache<K, V> {
  private readonly maxSize: number;
  private readonly ttlMs: number;
  private readonly cache = new Map<K, { value: V; timestamp: number }>();

  constructor(options?: { maxSize?: number; ttlMs?: number }) {
    this.maxSize = options?.maxSize ?? 50;
    this.ttlMs = options?.ttlMs ?? 300_000; // 5 minutes
  }

  /**
   * Get a value from cache. Returns undefined if not found or expired.
   * Moves the entry to "most recently used" position.
   */
  get(key: K): V | undefined {
    const entry = this.cache.get(key);
    if (!entry) return undefined;

    // Check TTL
    if (Date.now() - entry.timestamp > this.ttlMs) {
      this.cache.delete(key);
      return undefined;
    }

    // Move to end (most recently used) by re-inserting
    this.cache.delete(key);
    this.cache.set(key, entry);
    return entry.value;
  }

  /**
   * Set a value in cache. Evicts the least recently used entry if at capacity.
   */
  set(key: K, value: V): void {
    // If key exists, delete first to refresh order
    if (this.cache.has(key)) {
      this.cache.delete(key);
    }

    // Evict LRU (first entry in Map) if at capacity
    if (this.cache.size >= this.maxSize) {
      const firstKey = this.cache.keys().next().value;
      if (firstKey !== undefined) {
        this.cache.delete(firstKey);
      }
    }

    this.cache.set(key, { value, timestamp: Date.now() });
  }

  /**
   * Check if a key exists and is not expired.
   */
  has(key: K): boolean {
    return this.get(key) !== undefined;
  }

  /**
   * Remove all entries from cache.
   */
  clear(): void {
    this.cache.clear();
  }

  /**
   * Current number of entries in cache.
   */
  get size(): number {
    return this.cache.size;
  }
}
