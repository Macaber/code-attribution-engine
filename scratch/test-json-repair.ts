import { JsonRepairUtil } from '../src/core/utils/json-repair.util';

const testCases = [
  // 1. Literal newlines inside strings
  '{"text": "line1\nline2"}',
  // 2. Truncated JSON
  '{"text": "hello", "val": 1',
  // 3. The user\'s specific example (but with potential issues)
  '{"filePath": "D:\\code\\test.ts", "oldString": "function() {\n  test();\n}"}'
];

console.log('--- JSON Repair Test ---');

for (const tc of testCases) {
  try {
    console.log('Original:', JSON.stringify(tc));
    const repaired = JsonRepairUtil.repairAndParse(tc);
    console.log('Repaired:', JSON.stringify(repaired));
    console.log('---');
  } catch (e) {
    console.error('Failed to repair:', (e as Error).message);
    console.log('---');
  }
}
