const mysql = require('mysql2/promise');

async function test() {
  const pool = mysql.createPool({
    host: '127.0.0.1',
    user: 'root',
    password: '',
    database: 'code_attribution',
    port: 3306
  });

  try {
    // 1. Check if we can get any rows regardless of conditions
    const [allRows] = await pool.query('SELECT user_oa, created_at, NOW() as current_db_time FROM ai_messages ORDER BY created_at DESC LIMIT 5');
    console.log('Top 5 recent ai_messages:', allRows);
    
    // 2. Check the schema
    const [desc] = await pool.query('DESCRIBE ai_messages');
    console.log('\nTable schema:');
    desc.forEach(c => console.log(`${c.Field}: ${c.Type}`));
  } catch(e) {
    console.error('DB Error:', e.message);
  } finally {
    await pool.end();
  }
}
test();
