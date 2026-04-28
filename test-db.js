const mysql = require('mysql2/promise');
require('dotenv').config();

async function test() {
  const pool = mysql.createPool({
    host: process.env.MYSQL_HOST || 'localhost',
    user: process.env.MYSQL_USER || 'root',
    password: process.env.MYSQL_PASSWORD || '',
    database: process.env.MYSQL_DATABASE || 'test',
    port: process.env.MYSQL_PORT ? parseInt(process.env.MYSQL_PORT) : 3306
  });

  try {
    console.log(`Connecting to ${process.env.MYSQL_HOST}...`);
    // 1. Check if we can get any rows regardless of conditions
    const [allRows] = await pool.query('SELECT user_oa, created_at, NOW() as current_db_time FROM ai_messages ORDER BY created_at DESC LIMIT 5');
    console.log('Top 5 recent ai_messages:', allRows);
    
  } catch(e) {
    console.error('DB Error:', e.message);
  } finally {
    await pool.end();
  }
}
test();
