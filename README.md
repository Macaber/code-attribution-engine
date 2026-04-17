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

## 🔧 Diff 处理与 AI 归因流程

1. **Diff 分块**
   * `DiffParser` 解析 unified diff，将每个文件内连续的 `+` 行合并为一个 `DiffChunk`。
   * 也就是说：同一文件内连续新增行构成一个 chunk；不同文件、或遇到删除/上下文行时会拆成新的 chunk。
   * 每个 `DiffChunk` 包含文件路径、起始/结束行号、原始新增内容及规范化后的内容。

2. **Chunk 增强与调度**
   * `AttributionWorker` 读取 `doMerge` 的 `fileDetails`，把每个 chunk 和对应文件的完整 `code` 关联起来。
   * 同时统计每个文件的新增行数，用于 L3 的熔断保护。

3. **逐 chunk 对比 AI Messages**
   * 每个 `DiffChunk` 会依次对比所有用户历史 AI message。
   * `SimilarityEngine.evaluateChunk()` 对 chunk 内容和每条 AI 代码分别执行 L1/L2/L3。
   * 只保留分数最高的那条 AI message 作为该 chunk 的“最佳匹配”。
   * 如果某条消息在 L1 即命中 `STRICT`，则会提前终止该 chunk 的其余消息比较。

4. **Chunk 级归因**
   * 当前系统是“chunk 级别”归因：一个 chunk 最终只会分配给一个最佳 AI message。
   * 贡献行数根据匹配类型计算：
     * `STRICT` = 全部行数 × 1.0
     * 当发生深度重构时，采用行数与结构的乘积：`行数 × score`
     * 这避免了模糊统计带来的粗放归因误差。

5. **汇总输出**
   * `AttributionWorker.summarize()` 将所有 chunk 的 `contributedLines` 累加，生成总 AI 贡献行数与 AI 贡献比例。
   * 还会生成每条消息的贡献汇总和每个 chunk 的详细归因信息。

---

## 🧠 核心实现：并联仲裁管线 (Parallel Arbitration Pipeline)

系统放弃了传统的粗略串行淘汰漏斗，转而使用三层不同维度的探针独立比对，最终在 `SimilarityEngine` 判定结果时取 **最大归因价值 (MAX)**。

> **精确行溯源 (Line Tracking)**：在预处理阶段对代码去噪（剥离空格/换行）的同时，引擎构建了一层 `charToLineMap`，这意味着任何后续底层纯字符串层面的命中，系统都能把它**100% 反向穿透**对应回用户代码真实的某一行上。彻底终结了 `行数 * 分数` 的笼统乘法！

```
┌─────────────────────────────────────────────────┐
│  L1: Winnowing 文档指纹 (耗时: 极低)              │
│  ≥0.90 → STRICT ✅ 快速放行全量采纳               │
│  不满0.90 → 无论多少直接放行交由 L2 深究             │
├─────────────────────────────────────────────────┤
│  L2: LCS 矩阵回溯追踪 (耗时: 低，但精准)            │
│  根据回溯矩阵找出有效命中，反查还原原文件真实行号      │
│  只要揪出了实打实的复制行，强保底至少为 FUZZY ✅      │
├─────────────────────────────────────────────────┤
│  L3: AST 语义特征比对 (耗时: 中高, 条件触发)         │
│  专门对抗 L2 抓不到的"大面积方法更名、重构等变种破坏"    │
│  最终采纳: MAX (L2追踪出的真实复制行, L3估算出的重构行)  │
└─────────────────────────────────────────────────┘
```

### 第一层：Winnowing 文档指纹 (L1 - 粗筛层)

* **原理**: 通过滑动窗口提取长度为 $k$ 的 K-grams，计算每个 K-gram 的哈希值，在窗口 $w$ 内选取最小哈希值作为指纹。
* **度量**: Containment（包含度）-- "Diff 的指纹有多少在 AI 代码中找到？" 分母为 Diff 侧指纹数量。
* **短文本旁路**: 当规格化后文本长度 < K-gram 长度（默认 5）时，跳过 L1 直接进入 L2，避免 1-2 行短代码因无法生成指纹而被误判。

### 第二层：LCS 最长公共子序列精准追踪 (L2 - 行级溯源层)

* **原理**: 引擎在内存中平铺一维化二维规模的 DP 矩阵。不再只满足于算出最长公共字符数，而是通过 **Backtracking（回溯）** 逆向将所命中的所有有效共性字符位置全部提取。
* **映射锁定**: 拿着这些匹配位置去问预处理时留下的 `charToLineMap`，只要它发现原始代码的某一行被 AI 的字符重合覆盖率 $\ge 40\%$，直接将这一行作为强证据提取为 `exactContributedLines`。
* **安全底线**: 这是物理上无可辩驳的证据！只要该变量 $>0$，系统就认为该代码块存在确凿的搬运行为，不会被任何门槛（废除了 fastFail 漏斗）掩盖，保底直接定性为 `FUZZY`。

### 第三层：AST 语义特征比对 (L3 - 结构特征层)

* **原理**: 使用 Tree-sitter 解析**完整文件**生成有效 AST，但**仅提取 Diff 行范围内的 AST 节点特征**，避免无关代码稀释比较结果。
* **Diff 区域定位**: 通过 `chunk.startLine` / `chunk.endLine` 将文件 AST 节点按行号过滤，只有落在 Diff 范围内的节点才会生成特征。
* **度量**: Containment -- "AI 的结构特征有多少出现在 Diff 区域中？" 分母为 AI 侧特征数量。
* **L3 触发条件**: 若 `addedLineCount` 超过阈值、语言不支持、或完整文件内容不可用，则会跳过 L3，改为基于 L1+L2 加权回退结果。
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

1. 扫描时建立双向 `charToLineMap` 和 `lineCharCounts` 书签体系
2. 移除所有单行 (`//`) 和多行 (`/* */`) 注释
3. 移除所有空白字符（空格、制表符、换行符）
4. 将所有字符统一转换为小写

### 归因统计模型 (Attribution Model)

根据管线各层的判定结果对 Diff 中的新增代码行进行归因打分：

| 匹配类型 | 触发层 | 含义 | 贡献行数计算 |
|---|---|---|---|
| **STRICT** | L1 | 代码整体原封不动全搬自 AI | 取 L2 `exactLines` (未计算则全长兜底) |
| **FUZZY** | L2 | AI 献出物理片段，部分行被改 | 取 L2 的真实追溯行数 `exactLines` |
| **DEEP_REFACTOR** | L3 | 深度重构：全文件找不到照搬，但骨架抄袭 | 取 MAX( `L2 真实行`, `总行数 × L3 结构分`) |
| **NONE** | 任意 | 纯手工编写且结构对不上代码 | `0` |

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
npm test            # 85 tests, 7 suites
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
