# Question1：如果diff 代码来源于多个 ai message，当前处理能分析到吗

## 当前实现的行为

不完全能。

### 为什么

`AttributionWorker.processChunk()` 里是这样处理的：

- 对每个 `DiffChunk`
- 遍历所有 `aiMessages`
- 计算每个消息的 `evaluateChunk(...)`
- 只保留 `score` 最大的那个结果
- 最终把整个 `DiffChunk` 归到一个 `bestMessageId`

也就是说：

- 一个 `chunk` 只会被判给一个 AI message
- 即使这段新增代码实际上来源于多个消息
- 也不会在一个 chunk 内拆分成“多来源”

---

## 什么时候可以部分识别

只有当 diff 本来就被 `DiffParser` 分成多个 `DiffChunk` 时，才可能：

- 不同 chunk 分别匹配不同 AI message
- 这样才能反映“多条消息来源”

但如果一个 chunk 内混合了多个消息的代码，当前逻辑是无法细分的。

---

## 结论

- 当前算法可以识别“不同 chunk 对应不同消息”
- 但不能识别“同一个 chunk 内混合多个 AI message”
- 如果你要支持这种情况，需进一步做“chunk 细分”或“子块/行级”匹配逻辑

# Question2：当前 diff chunk 怎么分割的，按照不同文件的连续行作为一个 chunk 吗

## 当前 `DiffParser` 的分割规则就是

- 按每个文件处理
- 只看 `parse-diff` 解析出的 `chunk.changes`
- 连续的 `add` 行会被收集到同一个 `DiffChunk`
- 只要遇到非 `add` 行（context / delete），就会结束当前 chunk，并开始新 chunk
- 最终每个 `DiffChunk` 包含：
  - 文件路径
  - 起始/结束行号
  - 连续的新增行内容

## 所以本质上是

- “同一个文件内连续的新增行”作为一个 chunk
- 不同文件一定分成不同 chunk
- 一个 chunk 不会跨文件，也不会把中间有删除/上下文的断开部分合并在一起
