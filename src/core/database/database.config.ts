import mysql, { Pool, PoolOptions } from 'mysql2/promise';

/**
 * MySQL connection pool configuration.
 * Reads from environment variables with sensible defaults.
 */

let pool: Pool | null = null;

export function getMySQLPoolOptions(): PoolOptions {
  return {
    host: process.env.MYSQL_HOST ?? '127.0.0.1',
    port: parseInt(process.env.MYSQL_PORT ?? '3306', 10),
    user: process.env.MYSQL_USER ?? 'root',
    password: process.env.MYSQL_PASSWORD ?? '',
    database: process.env.MYSQL_DATABASE ?? 'code_attribution',
    waitForConnections: true,
    connectionLimit: parseInt(process.env.MYSQL_POOL_SIZE ?? '10', 10),
    queueLimit: 0,
    // Auto-reconnect on connection loss
    enableKeepAlive: true,
    keepAliveInitialDelay: 10_000,
  };
}

/**
 * Get or create the shared MySQL connection pool.
 */
export function getPool(): Pool {
  if (!pool) {
    pool = mysql.createPool(getMySQLPoolOptions());
  }
  return pool;
}

/**
 * Test the MySQL connection and log result.
 */
export async function testConnection(): Promise<boolean> {
  try {
    const conn = await getPool().getConnection();
    await conn.ping();
    conn.release();
    console.log('[MySQL] Connection pool initialized successfully');
    return true;
  } catch (error) {
    console.error('[MySQL] Failed to connect:', (error as Error).message);
    return false;
  }
}

/**
 * Gracefully close the connection pool.
 */
export async function closePool(): Promise<void> {
  if (pool) {
    await pool.end();
    pool = null;
    console.log('[MySQL] Connection pool closed');
  }
}
