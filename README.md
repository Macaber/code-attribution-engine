# OpenCode Attribution Engine (AI 代码采纳率归因分析引擎)

## 📌 项目背景 (Project Context)

本项目是一个旁路分析系统，用于统计开发者对 OpenCode（AI 辅助编程工具）生成代码的实际采纳率。系统通过接收 CICD 系统的 Webhook（`POST /api/coding/doMerge`），将用户提交的代码变更（Git Diff + 完整文件内容）与数据库中记录的该用户最近调用大模型生成的代码（AI Messages）进行异步的相似度比对，从而计算出真实的 AI 代码贡献占比。

## 🏗️ 系统架构与技术选型 (Architecture & Tech Stack)

* **语言**: TypeScript (Node.js) - 严格模式 (`"strict": true`)
* **框架**: Express（模块化路由）
* **队列**: BullMQ + Redis（用于解耦 Webhook 接收与高 CPU 消耗的代码比对任务）
* **AST 解析**: `web-tree-sitter` (WASM 版本，跨平台兼容，无 Node-GYP 编译依赖)
* **核心依赖**:
  * `parse-diff`: 解析 Git Diff 数据
  * `web-tree-sitter`: 将代码解析为 AST，提取语义特征
  * `tree-sitter-wasms`: 预编译的语法文件 (.wasm)

---

## 🔌 Webhook 接口 (API Endpoint)

### `POST /api/coding/doMerge`

接收 CICD 系统合并后的代码数据：

```json
{
  "oa": "codingadm",
  "sysCode": "sdss",
  "sysName": "cicd jenkinsFile",
  "repoName": "xxyjava",
  "mergeId": "1016978",
  "title": "test260311",
  "createTime": "2026-01-06 11:08:55",
  "detail": "[{\"path\":\"backend/Main.java\",\"code\":\"完整文件内容\",\"diff\":\"unified diff 文本\"}]"
}
```

**关键字段说明：**

| 字段 | 描述 |
|---|---|
| `oa` | 操作员账号（用于查询用户 AI 历史记录） |
| `mergeId` | 合并请求 ID（作为任务唯一标识） |
| `detail` | JSON 字符串化的数组，每个元素包含 `path`（文件路径）、`code`（合并后完整文件内容）、`diff`（该文件的 unified diff） |

> ⚠️ `code` 字段（完整文件内容）对于 L3 层 AST 解析至关重要。Git Diff 中的代码片段通常不完整（缺少类定义、括号不闭合），Tree-sitter 解析残缺代码会产生大量 `ERROR` 节点。系统通过解析完整文件生成完美的 AST，再结合 Diff 行号定位受影响的代码区域。

---

## 🧠 核心实现：漏斗式升维降级管线 (Escalation Pipeline)

系统采用**三层漏斗拦截模式（Chain of Responsibility）**，每一层都有独立的**快速放行（Fast-Path）**和**快速熔断（Fast-Fail）**阈值，确保在"性能"与"精准度"之间动态平衡。

> **核心度量统一为 Containment（包含度）**：三层算法的分母始终基于 Diff 侧（用户提交的代码），回答同一个业务问题 -- "用户提交的代码中有多少来源于 AI？"。这避免了用户部分采纳大段 AI 代码时被"分母拉低"的问题。

```
┌─────────────────────────────────────────────────┐
│  L1: Winnowing 文档指纹 (耗时: 极低)              │
│  ≥0.90 → STRICT ✅ 快速放行                      │
│  ≤0.15 → NONE ❌ 快速熔断                        │
│  中间值 → 降级到 L2                               │
├─────────────────────────────────────────────────┤
│  L2: LCS 最长公共子序列 (耗时: 低)                │
│  ≥0.80 → FUZZY ✅ 快速放行                       │
│  ≤0.30 → NONE ❌ 快速熔断                        │
│  中间值 → 降级到 L3                               │
├─────────────────────────────────────────────────┤
│  L3: AST 语义特征比对 (耗时: 中高, 条件触发)       │
│  仅针对模棱两可的代码触发 (变量全量重命名/逻辑重排)  │
│  ≥0.60 → DEEP_REFACTOR ✅                        │
│  <0.60 → NONE ❌                                 │
└─────────────────────────────────────────────────┘
```

### 第一层：Winnowing 文档指纹 (L1 - 粗筛层)

* **原理**: 通过滑动窗口提取长度为 $k$ 的 K-grams，计算每个 K-gram 的哈希值，在窗口 $w$ 内选取最小哈希值作为指纹。
* **度量**: Containment（包含度）-- "Diff 的指纹有多少在 AI 代码中找到？" 分母为 Diff 侧指纹数量。
* **短文本旁路**: 当规格化后文本长度 < K-gram 长度（默认 5）时，跳过 L1 直接进入 L2，避免 1-2 行短代码因无法生成指纹而被误判。
* **阈值**: 快速放行 $\ge 0.90$，快速熔断 $\le 0.15$

### 第二层：LCS 最长公共子序列 (L2 - 序列层)

* **原理**: 动态规划计算两段规格化文本的最长公共子序列。采用两行空间优化 ($O(\min(M,N))$ 空间) 并内置熔断器（$N \times M > 10^7$ 时按比例截断输入）。
* **公式**: $\text{相似度} = \frac{\text{LCS}(Code_{AI}, Code_{Diff})}{|Code_{Diff}|}$ -- 分母为 Diff 侧长度，用户只采纳 AI 部分代码时不会被分母拉低。
* **阈值**: 快速放行 $\ge 0.80$，快速熔断 $\le 0.30$

### 第三层：AST 语义特征比对 (L3 - 结构特征层)

* **原理**: 使用 Tree-sitter 解析**完整文件**生成有效 AST，但**仅提取 Diff 行范围内的 AST 节点特征**，避免无关代码稀释比较结果。
* **Diff 区域定位**: 通过 `chunk.startLine` / `chunk.endLine` 将文件 AST 节点按行号过滤，只有落在 Diff 范围内的节点才会生成特征。
* **度量**: Containment -- "AI 的结构特征有多少出现在 Diff 区域中？" 分母为 AI 侧特征数量。
* **提取的特征类型（增强版）**:
  * 函数调用 + 参数数量: `call:fetch`, `call:fetch/2`, `call:new:Map`
  * 控制流（细粒度）: `control:if` vs `control:if_else`, `control:for_in`, `control:for_each`, `control:do_while`, `control:ternary`, `control:await`, `control:yield`, `control:try_catch_finally`
  * 声明（细粒度）: `decl:function`, `decl:arrow`, `decl:method`, `decl:constructor`, `decl:getter`, `decl:setter`, `decl:interface`, `decl:enum`, `decl:fn_params/3`
  * 导入路径: `import:express`, `import:axios`
  * 运算符（增强）: `op:===`, `op:unary:!`, `op:aug_assign:+=`, `op:instanceof`, `op:typeof`
  * 字面量类型: `literal:string`, `literal:number`, `literal:boolean`, `literal:array`, `literal:object`
  * 类型注解 (TypeScript): `type:annotation`, `type:cast`, `type:assertion`
* **优势**: 即使用户将 AI 写的函数调换了位置、把变量全部重命名、或者把逻辑拆分成子函数，只要核心调用的 API 和控制流一致，包含度依然会很高。
* **阈值**: 通过 $\ge 0.60$

### L3 层熔断保护机制

引入 AST 和多层比对后，需要在架构上做以下防护：

| 熔断条件 | 行为 | 原因 |
|---|---|---|
| 文件新增行数 > 1000 行 | 跳过 L3，回退 L1+L2 加权 | 通常是引入第三方库或自动生成代码，防止 OOM |
| 文件类型不可解析 (`.properties`, `.json`, `.yaml` 等) | 跳过 L3 | 配置文件无有意义的 AST 特征 |
| 语言 Grammar (.wasm) 未加载 | 跳过 L3，降级 L1+L2 | 系统不能因冷门语言直接报错 |
| 无完整文件内容 (`code` 字段为空) | 跳过 L3 | 残缺代码 AST 解析会产生大量 ERROR 节点 |

### 预处理：规格化 (Normalization)

在进入比对算法前，消除代码格式差异带来的噪音：

1. 移除所有单行 (`//`) 和多行 (`/* */`) 注释
2. 移除所有空白字符（空格、制表符、换行符）
3. 将所有字符统一转换为小写

### 归因统计模型 (Attribution Model)

根据管线各层的判定结果对 Diff 中的新增代码行进行归因打分：

| 匹配类型 | 触发层 | 含义 | 贡献行数计算 |
|---|---|---|---|
| **STRICT** | L1 | 代码基本原封不动来自 AI | `行数 × 1.0` |
| **FUZZY** | L2 | AI 提供核心逻辑，用户有修改 | `行数 × score` |
| **DEEP_REFACTOR** | L3 | 深度重构：结构保留，表面改写 | `行数 × score` |
| **NONE** | 任意 | 纯手工编写代码 | `0` |

$$\text{AI 贡献行数} = \sum (\text{Diff 新增代码块行数} \times \text{对应匹配得分系数})$$

---

## 🏃 快速开始 (Quick Start)

```bash
# 安装依赖 (WASM 语法文件会自动随 tree-sitter-wasms 包安装)
npm install

# 运行测试
npm test

# 启动开发服务 (需要 Redis)
npm run dev
```

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `REDIS_HOST` | `127.0.0.1` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | (空) | Redis 密码 |
| `MYSQL_HOST` | `127.0.0.1` | MySQL 地址 |
| `MYSQL_PORT` | `3306` | MySQL 端口 |
| `MYSQL_USER` | `root` | MySQL 用户名 |
| `MYSQL_PASSWORD` | (空) | MySQL 密码 |
| `MYSQL_DATABASE` | `code_attribution` | 数据库名 |
| `PORT` | `3000` | HTTP 服务端口 |
| `WORKER_CONCURRENCY` | `2` | 并发处理任务数 |

---

## 📂 工程目录结构 (Directory Structure)

```text
src/
├── core/
│   ├── queue/                       # BullMQ 队列配置与生产者/消费者
│   │   ├── queue.config.ts          # Redis 连接 & 队列选项
│   │   ├── queue.producer.ts        # 任务入队
│   │   └── queue.consumer.ts        # 任务出队 & 调度 Worker + MySQL 持久化
│   ├── database/                    # MySQL 持久化层
│   │   ├── database.config.ts       # 连接池配置 (mysql2/promise)
│   │   ├── report.service.ts        # 报告写入 + 失败任务记录 + 重试管理
│   │   └── migrations/
│   │       └── 001_attribution_tables.sql  # 建表 DDL (3张表)
│   └── cache/
│       └── lru-cache.ts             # LRU 缓存 (AST 解析复用, 50 条/5 分钟 TTL)
├── domains/
│   ├── webhook/
│   │   └── webhook.controller.ts    # POST /api/coding/doMerge 接入层
│   └── attribution/                 # 👉 核心算法层
│       ├── normalizer.ts            # 预处理: 注释/空白/大小写清洗
│       ├── diff-parser.ts           # Git Diff 解析 → DiffChunk[]
│       ├── similarity-engine.ts     # 三层漏斗管线 (L1→L2→L3)
│       ├── attribution.worker.ts    # 管线编排器 (async, 文件上下文传递)
│       └── algorithms/
│           ├── winnowing.ts         # L1: 文档指纹
│           ├── lcs.ts               # L2: 最长公共子序列 (DP + 熔断器)
│           ├── ast-engine.ts        # L3: Tree-sitter AST 特征提取 + Jaccard
│           ├── language-map.ts      # 文件扩展名 → Grammar 映射
│           └── grammars/            # Tree-sitter .wasm 语法文件
│               ├── tree-sitter-typescript.wasm
│               ├── tree-sitter-java.wasm
│               ├── tree-sitter-javascript.wasm
│               ├── tree-sitter-python.wasm
│               ├── tree-sitter-css.wasm
│               ├── tree-sitter-go.wasm
│               ├── tree-sitter-c.wasm
│               ├── tree-sitter-cpp.wasm
│               └── tree-sitter-tsx.wasm
├── types/
│   └── index.ts                     # 全局 TS 接口与类型定义
├── app.ts                           # Express 应用配置
└── main.ts                          # 入口 & 优雅关闭
```

## 🧪 测试 (Tests)

```bash
npm test            # 83 tests, 7 suites
npm run test:coverage
```

| 测试套件 | 数量 | 覆盖内容 |
|---|---|---|
| `normalizer.test.ts` | 11 | 注释移除、空白清洗、大小写统一 |
| `winnowing.test.ts` | 10 | K-gram 生成、指纹一致性 |
| `lcs.test.ts` | 11 | DP 精度、空间优化、大输入熔断器 |
| `diff-parser.test.ts` | 8 | Diff 解析、多文件、边界情况 |
| `similarity-engine.test.ts` | 20 | L1 快速放行/熔断、L2 升级、L3 熔断保护、匹配类型映射 |
| `lru-cache.test.ts` | 9 | 缓存存取、LRU 淘汰、TTL 过期 |
| `language-map.test.ts` | 14 | 扩展名映射、不可解析文件过滤、L3 资格判定 |

## 🗺️ 支持的语言 (Supported Languages)

| 语言 | 文件扩展名 | L3 AST | Grammar 文件 |
|---|---|---|---|
| TypeScript | `.ts` | ✅ | `tree-sitter-typescript.wasm` |
| TSX | `.tsx` | ✅ | `tree-sitter-tsx.wasm` |
| JavaScript | `.js`, `.mjs`, `.cjs`, `.jsx` | ✅ | `tree-sitter-javascript.wasm` |
| Java | `.java` | ✅ | `tree-sitter-java.wasm` |
| Python | `.py` | ✅ | `tree-sitter-python.wasm` |
| CSS/LESS/SCSS | `.css`, `.less`, `.scss` | ✅ | `tree-sitter-css.wasm` |
| Go | `.go` | ✅ | `tree-sitter-go.wasm` |
| C | `.c`, `.h` | ✅ | `tree-sitter-c.wasm` |
| C++ | `.cpp`, `.cc`, `.cxx`, `.hpp` | ✅ | `tree-sitter-cpp.wasm` |
| 配置文件 | `.json`, `.yaml`, `.properties`, `.xml` 等 | ❌ 跳过 L3 | — |
| 未知语言 | 其他 | ❌ 降级 L1+L2 | — |
