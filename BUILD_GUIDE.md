# 快速启动指南

## 使用打包后的单文件应用

打包后的应用无需 node_modules，可以独立运行。

### 构建

```bash
npm run build
```

输出：`dist/bundle.js`（单个文件，包含所有依赖）

### 运行

```bash
# 直接运行（无需 npm install）
node dist/bundle.js

# 或使用 npm start
npm start
```

### Docker 运行

```bash
# 构建 Docker 镜像
docker build -t code-attribution-engine .

# 运行容器
docker run -p 3000:3000 code-attribution-engine
```

## 项目结构

- `npm run build` - 使用 esbuild 打包成单个 bundle.js
- `npm run build:tsc` - 原始 TypeScript 编译（保留用于参考）
- `npm start` - 运行打包后的应用
- `npm run dev` - 开发模式（使用 ts-node）
- `npm test` - 运行 Jest 测试

## 优势

✓ 无需依赖即可运行（除了 Node.js）
✓ Docker 镜像更小（无需复制 node_modules）
✓ 部署更简单
✓ 启动更快
