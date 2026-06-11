# OpenCode Attribution Engine (AI 代码采纳率归因分析引擎)

## 📌 项目背景 (Project Context)

本项目是一个旁路分析系统，用于统计开发者对 OpenCode（AI 辅助编程工具）生成代码的实际采纳率。系统通过接收 CICD 系统的 Webhook（`POST /api/coding/doMerge`），将用户提交的代码变更（Git Diff + 完整文件内容）与数据库中记录的该用户最近调用大模型生成的代码（AI Messages）进行异步的相似度比对，从而计算出真实的 AI 代码贡献占比。

## 🏗️ 系统架构与技术选型 (Architecture & Tech Stack)

* **语言**: Java 17
* **框架**: Spring Boot 3.2.4 + Spring Web
* **ORM / 数据库**: MyBatis-Plus 3.5.5 + MySQL 8.0
* **队列 & 缓存**: Redisson 3.27.2 + Redis 7 (基于 Redisson 阻塞双端队列 `RBlockingDeque` 进行异步任务调度)
* **AST 解析**: `io.github.bonede:tree-sitter` (JNI 本地绑定版，高性能语法分析)
* **核心依赖**:
  * `bonede:tree-sitter-java / javascript / typescript`: 预编译的 JNI 语法解析器
  * `lombok`: 简化实体与 DTO 开发
  * `jackson`: JSON 解析与对象转换

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

3. **逐 chunk 对比 AI Messages (多消息归因)**
   * 每个 `DiffChunk` 会依次对比所有用户历史 AI message。
   * `SimilarityEngine.evaluateChunk()` 对 chunk 内容和每条 AI 代码分别执行 L1/L2/L3。
   * 所有 L2 score >= 10% 且匹配类型非 `NONE` 的 AI message 都会被记录为该 chunk 的贡献消息。
   * 如果某条消息在 L1 即命中 `STRICT`，则会提前终止该 chunk 的其余消息比较。
   * 分数最高的 AI message 作为 `bestMatch`（驱动归因分类），所有贡献消息以逗号分隔存入 `matchedMessageIds`。

4. **Chunk 级归因 (跨消息去重)**
   * 每条贡献消息各自通过 LCS 回溯出 `contributedLineIndices`（具体哪些行被命中）。
   * 系统对所有贡献消息的行索引做 **Set Union（并集合并）**，同一行被多个消息命中时只算一次。
   * 最终 `contributedLines = union.size`，杜绝重复计算。
   * 贡献行数根据最佳匹配类型确定计算方式：
     * `STRICT` = union 追溯行数（未追溯到则全长兜底）
     * `FUZZY` = union 追溯行数
     * `DEEP_REFACTOR` = MAX(union 追溯行数, 总行数 x L3 结构分)

5. **汇总输出**
   * `AttributionWorker.summarize()` 将所有 chunk 的 `contributedLines` 累加，生成总 AI 贡献行数与 AI 贡献比例。
   * `messageBreakdown` 中每条 AI message 的贡献行数按参与消息数等比分摊。
   * 每个 chunk 的详细归因信息包含 `matchedMessageIds`（逗号分隔），支持完整的数据库关联回溯。

---

## 🧠 核心实现：并联仲裁管线 (Parallel Arbitration Pipeline)

系统放弃了传统的粗略串行淘汰漏斗，转而使用三层不同维度的探针独立比对，最终在 `SimilarityEngine` 判定结果时取 **最大归因价值 (MAX)**。

> **精确行溯源 (Line Tracking)**：在预处理阶段对代码去噪（按 Token/词法块切分）的同时，引擎构建了一层 `tokenToLineMap`，这意味着任何后续底层纯 Token 层面（而非字符层面）的命中，系统都能把它**100% 反向穿透**对应回用户代码真实的某一行上。彻底终结了 `行数 * 分数` 的笼统乘法！

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

* **原理**: 引擎在内存中平铺一维化二维规模的 DP 矩阵。系统**不再逐字符计算**，而是将代码降维为 **Token（词法块）序列**，彻底解决了万字长文导致内存溢出的问题。通过 **Backtracking（回溯）** 逆向将所命中的所有有效 Token 位置全部提取。
* **映射锁定**: 拿着这些匹配位置去问预处理时留下的 `tokenToLineMap`，只要它发现原始代码的某一行被 AI 的 Token 重合覆盖率 $\ge 70\%$，直接将这一行作为强证据提取为 `exactContributedLines`。
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

1. **保留注释**：系统特意**保留注释**进行相似度比对。如果 AI 生成了注释并且用户采纳了它们，这也算作 AI 的贡献。
2. **剔除空白**：移除行内所有的空白字符（空格、换行、制表符）。
3. **大小写归一**：将字符统一转换为小写。
4. **建立物理行号映射**：对于非空行，建立从“规格化后的行”到“原始未规格化代码物理行号”的反向追溯映射（LineMapping），以实现精准的行级物理追踪。

### 归因统计模型 (Attribution Model)

根据管线各层的判定结果对 Diff 中的新增代码行进行归因打分：

| 匹配类型 | 触发层 | 含义 | 贡献行数计算 |
|---|---|---|---|
| **STRICT** | L1 | 代码整体原封不动全搬自 AI | union 合并追溯行数（未追溯到则全长兜底） |
| **FUZZY** | L2 | AI 献出物理片段，部分行被改 | union 合并追溯行数（跨消息去重） |
| **DEEP_REFACTOR** | L3 | 深度重构：全文件找不到照搬，但骨架抄袭 | MAX( union 追溯行数, 总行数 × L3 结构分 ) |
| **NONE** | 任意 | 纯手工编写且结构对不上代码 | `0` |

$$\text{AI 贡献行数} = \sum \text{Union}(\text{各贡献消息的 contributedLineIndices})$$

> **多消息归因说明**：一个 chunk 可能同时关联多条 AI message。系统对所有 L2 score ≥ 10% 的消息做 Set Union 去重，确保同一行只计一次。关联的消息 ID 以逗号分隔存入 `matchedMessageIds` 字段。

---

## 🏃 快速开始 (Quick Start)

```bash
# 本地编译与构建 (跳过测试)
mvn clean package -DskipTests

# 运行所有单元测试
mvn clean test

# 启动 Spring Boot 本地开发服务
mvn spring-boot:run
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
src/main/
├── java/com/macaber/attribution/
│   ├── config/                     # 配置层 (SimilarityEngine, MyBatis-Plus, Web)
│   ├── controller/                 # Web 接口控制层 (Webhook, Reports 报表)
│   ├── dao/                        # 数据库访问层 (MyBatis-Plus Mapper)
│   ├── dto/                        # 数据传输对象 (Payload, DTO, JobData)
│   ├── entity/                     # 数据库映射实体 (Result, ChunkDetail)
│   ├── service/                    # 业务逻辑接口及实现类 (Service & ServiceImpl)
│   ├── util/                       # 工具类 (LRU Cache 等)
│   ├── core/                       # 👉 核心归因 analysis 管线与算法实现
│   │   ├── queue/                  # Redisson 队列生产者/消费者
│   │   ├── AstFeatureEngine.java   # L3 AST 语法分析引擎 (Tree-sitter)
│   │   ├── LCS.java                # L2 最长公共子序列算法
│   │   ├── Winnowing.java          # L1 指纹过滤算法
│   │   ├── Normalizer.java         # 文本规格化预处理器
│   │   ├── DiffParser.java         # Unified Diff 解析器
│   │   ├── SimilarityEngine.java   # 并联管线核心控制引擎
│   │   └── AttributionFilter.java  # 大文件与二进制过滤层
│   └── AttributionEngineApplication.java # Spring Boot 启动入口
└── resources/
    ├── application.properties      # 系统核心配置文件
    └── migrations/                 # 数据库初始化与升级 SQL 脚本
```
## 🔄 任务失败处理与重试机制 (Failed Job & Retry Mechanism)

由于系统是纯 Java 项目，并未内置复杂的后台轮询重试调度器。系统的错误重试与保障设计如下：

1. **优雅停机与任务即时回滚 (Graceful Shutdown & Rollback)**
   * 当工作线程执行任务过程中，由于系统重启、JVM 关闭或线程池关闭收到 `InterruptedException` 时，系统会捕获该异常。
   * 系统通过双端阻塞队列的 `queue.addFirst(jobData)` 方法，将正在被中断的任务**即时回滚送回 Redis 队列头部**，防止任务丢失，供重启后的新实例或其它 Worker 线程继续拉取重试。

2. **持久化失败任务记录 (Failed Job Log)**
   * 遇上非中断的业务/系统报错（如数据库连接断开、大模型接口失败等），Worker 会捕获异常，并精简异常信息和堆栈（过滤冗余的框架代码，仅保留 Top 8 帧和 `com.macaber.` 业务帧），然后写入 `attribution_failed_jobs` 失败任务表。
   * 该表中的 `job_data` 列以 JSON 格式完整记录了任务 Payload。
   * 数据表中的 `status` 字段包含 `pending`（待重试）、`retrying`（重试中）、`resolved`（已解决）和 `abandoned`（已放弃）状态。

3. **异步/人工重试设计 (External / Manual Retry)**
   * 运维人员或自动化脚本可通过读取 `attribution_failed_jobs` 表中为 `pending` 状态的记录，提取其 `job_data` Payload 重新发送到 Redis 的 `attribution-queue` 队列中完成重试。

---

## 🧪 测试 (Tests)

使用 Maven 运行 JUnit 5 单元测试：

```bash
mvn test
```

### 测试类与覆盖内容 (Test Suites & Coverage)

| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `NormalizerTest` | 5 | 注释保留比对、空白清洗、大小写统一、行定位映射 |
| `WinnowingTest` | 3 | K-gram 生成、指纹一致性、包含度度量 |
| `LcsTest` | 8 | LCS 最长公共子序列、逆向回溯、Token 降维 |
| `DiffParserTest` | 4 | Unified Diff 解析、多文件、空白/边界情况 |
| `SimilarityEngineTest` | 13 | L1 快速放行、L2 LCS 物理行追溯、L3 AST 包含度判定、综合管线决策 |
| `AttributionFilterTest` | 10 | 文件后缀过滤、大文件熔断、二进制文件检测、大 Diff 保护 |
| `AttributionWorkerTest` | 2 | 失败任务的异常消息及异常堆栈精简机制 |
| `ReportControllerTest` | 2 | 报告查询分页列表、聚合概览、单报告及贡献消息详情 API 验证 |

---

## 🗺️ 支持的语言 (Supported Languages)

| 语言 | 文件扩展名 | L3 AST (基于 io.github.bonede JNI 库) |
|---|---|---|
| Java | `.java` | ✅ 支持 (tree-sitter-java) |
| JavaScript | `.js`, `.mjs`, `.cjs`, `.jsx` | ✅ 支持 (tree-sitter-javascript) |
| TypeScript / TSX | `.ts`, `.tsx` | ✅ 支持 (tree-sitter-typescript) |
| 配置文件 | `.json`, `.yaml`, `.properties`, `.xml` 等 | ❌ 跳过 L3 |
| 未知语言 | 其他 | ❌ 降级 L1+L2 |

