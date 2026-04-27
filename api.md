# Code Attribution Engine — API 文档

> Base URL: `http://localhost:3000`

---

## 目录

- [健康检查](#健康检查)
- [Webhook 接口](#webhook-接口)
  - [POST /api/coding/doMerge](#post-apicodingdomerge)
- [报告查询接口](#报告查询接口)
  - [GET /api/reports](#get-apireports)
  - [GET /api/reports/stats/summary](#get-apireportsstatssummary)
  - [GET /api/reports/:mergeId](#get-apireportsmergeid)

---

## 健康检查

### `GET /health`

服务健康状态检查。

**Response** `200 OK`

```json
{
  "status": "ok",
  "service": "code-attribution-engine",
  "timestamp": "2026-04-27T07:15:30.000Z"
}
```

---

## Webhook 接口

### `POST /api/coding/doMerge`

接收 CICD 系统的 Merge 回调，解析 diff 数据并提交归因分析任务到队列。

**Request Body** `application/json`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `oa` | string | 是 | 操作员账号 |
| `sysCode` | string | 否 | 系统代码 |
| `sysName` | string | 否 | 系统名称 |
| `repoName` | string | 是 | 仓库名 |
| `mergeId` | string | 是 | Merge Request ID |
| `title` | string | 否 | Merge 标题 |
| `createTime` | string | 否 | 创建时间 |
| `detail` | string | 是 | JSON 字符串，数组格式：`[{path, code, diff}]` |

**detail 数组元素结构：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `path` | string | 文件路径 |
| `code` | string | 完整合并后文件内容 |
| `diff` | string | unified diff 字符串 |

**Request 示例：**

```json
{
  "oa": "yfsun",
  "sysCode": "ELLM",
  "sysName": "cicd jenkinsFile",
  "repoName": "ellm-aicode",
  "mergeId": "MR-20260427-001",
  "title": "feat: 新增单元测试",
  "createTime": "2026-04-27 15:00:00",
  "detail": "[{\"path\":\"src/Test.java\",\"code\":\"...\",\"diff\":\"diff --git ...\"}]"
}
```

**Response:**

- `202 Accepted` — 任务已入队

```json
{
  "status": "accepted",
  "mergeId": "MR-20260427-001",
  "repoName": "ellm-aicode",
  "filesCount": 1,
  "message": "Attribution analysis job queued"
}
```

- `200 OK` — 无 diff 可分析，跳过

```json
{
  "status": "skipped",
  "mergeId": "MR-20260427-001",
  "message": "No file diffs to analyze"
}
```

- `400 Bad Request` — 参数缺失或 detail 格式错误

```json
{
  "error": "Missing required fields: mergeId, repoName, detail"
}
```

---

## 报告查询接口

> 以下接口需要数据库连接可用。若服务启动时 MySQL 未连接，这些接口不会注册。

### `GET /api/reports`

分页查询归因报告列表，支持多维筛选和排序。

**Query Parameters:**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `page` | number | 否 | 1 | 页码 |
| `pageSize` | number | 否 | 20 | 每页条数（最大 100） |
| `userId` | string | 否 | — | 按操作员账号精确筛选 |
| `repoName` | string | 否 | — | 按仓库名模糊匹配 |
| `sysCode` | string | 否 | — | 按系统代码精确筛选 |
| `startDate` | string | 否 | — | 起始时间（ISO 格式，如 `2026-04-01`） |
| `endDate` | string | 否 | — | 结束时间（ISO 格式） |
| `sortBy` | string | 否 | `created_at` | 排序字段：`created_at` / `ai_contribution_ratio` / `analyzed_lines` |
| `sortOrder` | string | 否 | `desc` | 排序方向：`desc` / `asc` |

**请求示例：**

```
GET /api/reports?userId=yfsun&repoName=ellm&page=1&pageSize=10&sortBy=ai_contribution_ratio&sortOrder=desc
```

**Response** `200 OK`

```json
{
  "data": [
    {
      "id": 42,
      "mergeId": "MR-20260427-001",
      "repoName": "ellm-aicode",
      "userId": "yfsun",
      "sysCode": "ELLM",
      "title": "feat: 新增图片审核单元测试",
      "totalCodeLines": 1580,
      "diffLines": 126,
      "analyzedLines": 108,
      "aiContributedLines": 85.5,
      "aiContributionRatio": 0.7917,
      "skippedLines": 1454,
      "skippedFileCount": 12,
      "strictMatches": 3,
      "fuzzyMatches": 2,
      "deepRefactorMatches": 0,
      "noMatches": 1,
      "elapsedMs": 342,
      "createdAt": "2026-04-27T07:15:30.000Z"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 10,
    "total": 56,
    "totalPages": 6
  }
}
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `totalCodeLines` | 所有文件总行数（合并后完整文件） |
| `diffLines` | Diff 原始行数（含空行） |
| `analyzedLines` | 实际分析的非空行数（AI 贡献率的分母） |
| `aiContributedLines` | AI 贡献行数 |
| `aiContributionRatio` | AI 贡献率 = `aiContributedLines / analyzedLines` |
| `skippedLines` | 跳过的行数（无 diff 的文件） |
| `skippedFileCount` | 跳过的文件数 |
| `strictMatches` | STRICT 匹配（L1 指纹完全匹配）的 chunk 数 |
| `fuzzyMatches` | FUZZY 匹配（L2 LCS 部分匹配）的 chunk 数 |
| `deepRefactorMatches` | DEEP_REFACTOR 匹配（L3 AST 结构匹配）的 chunk 数 |
| `noMatches` | 无匹配的 chunk 数 |
| `elapsedMs` | 处理耗时（毫秒） |

---

### `GET /api/reports/stats/summary`

全局统计概览，支持与列表接口相同的筛选条件。

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `userId` | string | 否 | 按操作员账号精确筛选 |
| `repoName` | string | 否 | 按仓库名模糊匹配 |
| `sysCode` | string | 否 | 按系统代码精确筛选 |
| `startDate` | string | 否 | 起始时间（ISO 格式） |
| `endDate` | string | 否 | 结束时间（ISO 格式） |

**请求示例：**

```
GET /api/reports/stats/summary?userId=yfsun&startDate=2026-04-01
```

**Response** `200 OK`

```json
{
  "totalReports": 156,
  "totalAnalyzedLines": 12580,
  "totalAiContributedLines": 8340.5,
  "avgAiContributionRatio": 0.6631,
  "matchDistribution": {
    "strict": 420,
    "fuzzy": 180,
    "deepRefactor": 35,
    "none": 90
  }
}
```

| 字段 | 说明 |
|------|------|
| `totalReports` | 报告总数 |
| `totalAnalyzedLines` | 分析的总非空行数 |
| `totalAiContributedLines` | AI 贡献总行数 |
| `avgAiContributionRatio` | 平均 AI 贡献率 |
| `matchDistribution` | 各匹配类型的 chunk 总数分布 |

---

### `GET /api/reports/:mergeId`

查询单个 Merge 的归因报告详情，包含 chunk 级明细和 AI 消息维度聚合。

**Path Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `mergeId` | string | 是 | Merge Request ID |

**请求示例：**

```
GET /api/reports/MR-20260427-001
```

**Response** `200 OK`

```json
{
  "report": {
    "id": 42,
    "mergeId": "MR-20260427-001",
    "repoName": "ellm-aicode",
    "userId": "yfsun",
    "sysCode": "ELLM",
    "title": "feat: 新增图片审核单元测试",
    "totalCodeLines": 1580,
    "diffLines": 126,
    "analyzedLines": 108,
    "aiContributedLines": 85.5,
    "aiContributionRatio": 0.7917,
    "skippedLines": 1454,
    "skippedFileCount": 12,
    "strictMatches": 3,
    "fuzzyMatches": 2,
    "deepRefactorMatches": 0,
    "noMatches": 1,
    "elapsedMs": 342,
    "createdAt": "2026-04-27T07:15:30.000Z"
  },
  "chunkDetails": [
    {
      "id": 101,
      "filePath": "src/test/java/SitImageInspectBizImplTest.java",
      "startLine": 376,
      "endLine": 397,
      "totalLines": 22,
      "attribution": "strict",
      "contributedLines": 19,
      "matchedMessageId": "88201",
      "score": 0.9523,
      "matchType": "STRICT",
      "level": "L1"
    }
  ],
  "messageBreakdown": [
    {
      "messageId": "88201",
      "contributedLines": 19,
      "chunkCount": 1,
      "matchTypes": ["strict"]
    }
  ]
}
```

**chunkDetails 字段说明：**

| 字段 | 说明 |
|------|------|
| `filePath` | 文件路径 |
| `startLine` / `endLine` | Diff 起止行号 |
| `totalLines` | chunk 总行数 |
| `attribution` | 归因类型：`strict` / `fuzzy` / `deep_refactor` / `none` |
| `contributedLines` | 该 chunk 中 AI 贡献的行数 |
| `matchedMessageId` | 匹配到的 AI 消息 ID（溯源） |
| `score` | 相似度分数 (0.0 – 1.0) |
| `matchType` | 匹配类型：`STRICT` / `FUZZY` / `DEEP_REFACTOR` / `NONE` |
| `level` | 管线层级：`L1` / `L2` / `L3` / `FAILED_ALL` |

**messageBreakdown 字段说明：**

| 字段 | 说明 |
|------|------|
| `messageId` | AI 消息 ID |
| `contributedLines` | 该消息贡献的总行数 |
| `chunkCount` | 匹配到的 chunk 数量 |
| `matchTypes` | 涉及的归因类型列表 |

**Error Responses:**

- `400 Bad Request` — 缺少 mergeId 参数
- `404 Not Found` — 未找到对应报告

```json
{
  "error": "Report not found for mergeId: MR-XXXXX"
}
```

---

## 通用错误响应

所有接口在遇到服务端异常时返回：

**`500 Internal Server Error`**

```json
{
  "error": "Internal server error"
}
```
