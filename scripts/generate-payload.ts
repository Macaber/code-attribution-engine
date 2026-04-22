import * as fs from 'fs';
import * as path from 'path';

/**
 * 这是一个辅助脚本，用于将原生的 git diff 文本转换为 doMerge 接口所需的 JSON 请求体。
 * 
 * 用法:
 * 1. 将你的 git diff 内容保存到一个文本文件中，例如 `diff.txt`
 * 2. 运行脚本: `npx ts-node scripts/generate-payload.ts diff.txt`
 * 3. 脚本会在当前目录生成 `domerge-payload.json`
 */

function generatePayload() {
  const diffFilePath = process.argv[2];
  
  if (!diffFilePath) {
    console.error('❌ 缺少输入文件参数！');
    console.error('用法: npx ts-node scripts/generate-payload.ts <path-to-diff-file>');
    process.exit(1);
  }

  if (!fs.existsSync(diffFilePath)) {
    console.error(`❌ 找不到文件: ${diffFilePath}`);
    process.exit(1);
  }

  const diffContent = fs.readFileSync(diffFilePath, 'utf8');
  const lines = diffContent.split('\n');
  
  const fileDetails: { path: string; code: string; diff: string[] }[] = [];
  let currentFile: { path: string; code: string; diff: string[] } | null = null;

  for (const line of lines) {
    if (line.startsWith('diff --git')) {
      if (currentFile) {
        fileDetails.push(currentFile);
      }
      // 提取文件路径: diff --git a/src/foo b/src/foo
      const match = line.match(/diff --git a\/(.*?) b\/(.*)/);
      const filePath = match ? match[2] : 'unknown';
      
      currentFile = {
        path: filePath,
        diff: [line],
        code: ''
      };

      // 尝试从本地文件系统读取完整代码 (AST 解析需要)
      try {
        const absolutePath = path.resolve(process.cwd(), filePath);
        if (fs.existsSync(absolutePath)) {
          currentFile.code = fs.readFileSync(absolutePath, 'utf8');
        } else {
          console.warn(`⚠️ 无法在本地找到文件以提取完整代码: ${filePath} (AST 解析可能受限)`);
        }
      } catch (e) {
        // ignore
      }
    } else if (currentFile) {
      currentFile.diff.push(line);
    }
  }

  if (currentFile) {
    fileDetails.push(currentFile);
  }

  if (fileDetails.length === 0) {
    console.error('❌ 在输入文件中没有找到有效的 git diff 块 (缺少 diff --git ...)');
    process.exit(1);
  }

  // 构造完整的 Payload
  const payload = {
    oa: "test_user",           // 可以根据需要修改
    sysCode: "SYS001",
    sysName: "Test System",
    repoName: "test-repo",
    mergeId: `MR-${Date.now()}`,
    title: "Test Merge Request",
    createTime: new Date().toISOString(),
    // 将详情数组序列化为 JSON 字符串，以严格符合 API 类型定义
    detail: JSON.stringify(fileDetails.map(f => ({
      path: f.path,
      code: f.code,
      diff: f.diff.join('\n')
    })))
  };

  const outputPath = path.resolve(process.cwd(), 'domerge-payload.json');
  fs.writeFileSync(outputPath, JSON.stringify(payload, null, 2), 'utf8');
  
  console.log(`✅ 成功解析了 ${fileDetails.length} 个文件的 diff`);
  console.log(`✅ doMerge 请求体已生成并保存到: ${outputPath}`);
  console.log(`\n你可以使用 curl 或 Postman 测试该接口:`);
  console.log(`curl -X POST http://localhost:3000/api/coding/doMerge \\`);
  console.log(`  -H "Content-Type: application/json" \\`);
  console.log(`  -d @domerge-payload.json`);
}

generatePayload();
