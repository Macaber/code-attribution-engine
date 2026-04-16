import { LRUCache } from '../lru-cache';

describe('LRUCache', () => {
  it('should store and retrieve values', () => {
    const cache = new LRUCache<string, number>();
    cache.set('a', 1);
    cache.set('b', 2);
    expect(cache.get('a')).toBe(1);
    expect(cache.get('b')).toBe(2);
  });

  it('should return undefined for missing keys', () => {
    const cache = new LRUCache<string, number>();
    expect(cache.get('missing')).toBeUndefined();
  });

  it('should evict least recently used when at capacity', () => {
    const cache = new LRUCache<string, number>({ maxSize: 3 });
    cache.set('a', 1);
    cache.set('b', 2);
    cache.set('c', 3);
    cache.set('d', 4); // should evict 'a'

    expect(cache.get('a')).toBeUndefined();
    expect(cache.get('b')).toBe(2);
    expect(cache.get('c')).toBe(3);
    expect(cache.get('d')).toBe(4);
    expect(cache.size).toBe(3);
  });

  it('should refresh order on get (make recently used)', () => {
    const cache = new LRUCache<string, number>({ maxSize: 3 });
    cache.set('a', 1);
    cache.set('b', 2);
    cache.set('c', 3);

    // Access 'a' to make it recently used
    cache.get('a');

    // Now add 'd' — should evict 'b' (least recently used), not 'a'
    cache.set('d', 4);

    expect(cache.get('a')).toBe(1);
    expect(cache.get('b')).toBeUndefined();
    expect(cache.get('c')).toBe(3);
    expect(cache.get('d')).toBe(4);
  });

  it('should expire entries after TTL', () => {
    const cache = new LRUCache<string, number>({ ttlMs: 50 });
    cache.set('a', 1);

    expect(cache.get('a')).toBe(1);

    // Wait for TTL to expire
    return new Promise<void>((resolve) => {
      setTimeout(() => {
        expect(cache.get('a')).toBeUndefined();
        resolve();
      }, 80);
    });
  });

  it('should overwrite existing keys', () => {
    const cache = new LRUCache<string, number>();
    cache.set('a', 1);
    cache.set('a', 99);
    expect(cache.get('a')).toBe(99);
  });

  it('should report correct size', () => {
    const cache = new LRUCache<string, number>();
    expect(cache.size).toBe(0);
    cache.set('a', 1);
    expect(cache.size).toBe(1);
    cache.set('b', 2);
    expect(cache.size).toBe(2);
  });

  it('should clear all entries', () => {
    const cache = new LRUCache<string, number>();
    cache.set('a', 1);
    cache.set('b', 2);
    cache.clear();
    expect(cache.size).toBe(0);
    expect(cache.get('a')).toBeUndefined();
  });

  it('should has() return false for expired entries', () => {
    const cache = new LRUCache<string, number>({ ttlMs: 1 });
    cache.set('a', 1);

    return new Promise<void>((resolve) => {
      setTimeout(() => {
        expect(cache.has('a')).toBe(false);
        resolve();
      }, 20);
    });
  });
});
