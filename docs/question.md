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

# Question3：lcs 计算的时候不是把一个 chunk 打包成一个 string和 ai message 做匹配吗？怎么计算出每一行有多少个字符被匹配

**字符级溯源映射（Character-to-Line Mapping）**。

在真正丢给 LCS 算法之前，代码会先经过 `Normalizer` 类的 `normalizeWithMapping()` 方法处理。

它不是简单地把整个 chunk 一股脑变成一个 string，而是在拼接的过程中，**额外维护了一个数组 `charToLineMap`，用来记录新字符串中每一个字符，原本属于哪一行**。

### 举个具体例子：

假设用户提交了这样一段代码（2 行）：
```java
0: String a = "hello";
1: return a;
```

`Normalizer` 会按行进行“去噪”（去空格、转小写），并把它们拼起来：

1. **处理第 0 行**：变成 `stringa="hello";`（16 个字符）。
   此时，`charToLineMap` 里会被塞入 16 个 `0`。
2. **处理第 1 行**：变成 `returna;`（8 个字符）。
   接着，`charToLineMap` 里会被塞入 8 个 `1`。

最终，我们得到了两样东西：
- **`normalizedText` (打包后的长字符串)**: `"stringa="hello";returna;"` (24 个字符)
- **`charToLineMap` (字符的身世字典)**: `[0, 0, 0, 0, ..., 0, 1, 1, 1, 1, 1, 1, 1, 1]` (前面 16 个 0，后面 8 个 1)

### LCS 算完之后如何还原？

LCS 在这 24 个字符里算完后，它并不返回匹配的字符串，而是**返回匹配上的字符下标 (indices)**。

假设 LCS 算法告诉你：“你在 `normalizedText` 里的第 15、16、17、18 个字符匹配上了！”

接下来引擎就会去查 `charToLineMap`：
- 第 15 个字符属于哪行？`charToLineMap[15] == 0`（第 0 行 matched 计数 +1）
- 第 16 个字符属于哪行？`charToLineMap[16] == 1`（第 1 行 matched 计数 +1）
- 第 17 个字符属于哪行？`charToLineMap[17] == 1`（第 1 行 matched 计数 +1）
- 第 18 个字符属于哪行？`charToLineMap[18] == 1`（第 1 行 matched 计数 +1）

通过这种方式，不管 LCS 在长字符串里怎么天马行空地跨行匹配，引擎都能像查字典一样，把匹配到的每一个字符**精准地算回它原本所在的行号**上。