# Code Attribution Engine: 核心归因算法解析

代码归因引擎（Code Attribution Engine）采用了一种**漏斗式（Funnel-style）三层短路算法架构**，旨在平衡"高吞吐性能"与"高精准度"。

整体架构从轻到重分为三层：
1. **L1 Winnowing** (K-gram 滚动哈希指纹匹配)
2. **L2 LCS** (最长公共子序列行级匹配)
3. **L3 AST** (抽象语法树结构特征匹配)

引擎会根据上一层的计算得分，决定是立即短路返回（Fast-Pass / Fast-Fail），还是继续下沉到更重的一层进行深度比对。

---

## L1: Winnowing (指纹匹配)

**核心原理：基于 K-gram 的文档指纹技术**

Winnowing 将去噪后的代码切割成长度为 `k` 的滑动窗口（k-gram），并计算每个窗口的 Hash 值。在一个定义好的窗口大小（Window Size）内，只选取最小的 Hash 值作为该代码的"局部指纹"。

**在系统中的作用：**
第一道防线，以极低的性能开销，极速识别**大面积原封不动搬运**或**彻底无关**的代码。

### 示例分析

**AI 生成代码：**
```java
public String generateOrderNumber() {
    String prefix = "ORD-";
    String uuid = UUID.randomUUID().toString();
    return prefix + uuid.substring(0, 8);
}
```

**场景 A：用户原样复制**
*   **引擎处理**：提取出的指纹集合（如 `[H1, H5, H12, H18]`）与 AI 代码的指纹集合**完全一致**。
*   **结果**：交集匹配率 `score = 1.0`。
*   **动作**：大于 `0.80` 的门槛，**触发 L1 Fast-Pass**，判定为 `STRICT`，结束归因。

**场景 B：用户仅仅修改了变量名**
```java
public String makeOrderNum() {
    String pfx = "ORD-";
    String id = UUID.randomUUID().toString();
    return pfx + id.substring(0, 8);
}
```
*   **引擎处理**：因为指纹是基于连续 `k` 个字符计算的。哪怕只改了 `generateOrderNumber` 为 `makeOrderNum`，所有滑过这个词的 `k-gram` 窗口的 Hash 值**全部都会改变**。
*   **结果**：交集匹配率暴跌，可能 `score < 0.30`。
*   **动作**：这就是 Winnowing 的**局限性（对微调极度敏感）**。由于分数介于 `0.15` 和 `0.80` 之间，系统不会判定失败，而是**放行进入 L2** 进行更精细的比对。

---

## L2: LCS (最长公共子序列)

**核心原理：基于动态规划的行级序列比对**

LCS 寻找的是两个序列中，保持相对顺序不变的最长公共子序列。系统将 **每一行归一化后的文本** 作为 LCS 的最小比较单元（原子单位），直接判定哪些行来自 AI。

**在系统中的作用：**
作为中坚力量，专门捕获**"原样搬运、仅调整了缩进/大小写"**的代码采纳行为。同时，L2 直接产出**精准的行级贡献（exactContributedLines）**——命中即为 1 行贡献，无需额外阈值。

### 核心机制：行级归一化与匹配 (Line-level Normalization & LCS)

系统对 AI 代码和 Diff Chunk 各自按行拆分，每行独立做归一化（去除所有空白字符 + 转小写），然后以**整行字符串**为单元做 LCS DP。

**处理示例：**
```java
0: String myVal = imageService.createUuid();
1: return myVal;
```

**1. 行级归一化：**
系统对每一行去除所有空白并转为小写（**注意：系统会特意保留注释**，因为如果用户采纳了 AI 写的注释，也算作 AI 的贡献），得到 `normalizedLines` 数组：
```
["stringmyval=imageservice.createuuid();", "returnmyval;"]
```
同时记录 `originalLineIndices = [0, 1]`，用于将匹配结果映射回原始行号。

**2. 行级 LCS 匹配：**
假设原 AI 代码归一化后为：
```
["stringid1=sitimageinspectbizimpl.generateuniqueid();", "returnid1;"]
```
LCS 在两个 `normalizedLines` 数组间寻找相同的行。由于 `"stringmyval=..."` ≠ `"stringid1=..."`，第 0 行**不匹配**；`"returnmyval;"` ≠ `"returnid1;"`，第 1 行也**不匹配**。

**3. 行级判定：**
匹配即贡献、不匹配即不贡献——**没有中间态，没有阈值**。

> **设计理念：** 行级 LCS 采用**全匹配**逻辑。行内只要有任何修改（哪怕只改了一个变量名），归一化后字符串不同，该行就不算 AI 贡献。这是有意的设计取舍：
> - 改了变量名 → 不计入 AI 贡献 → **合理**（说明用户进行了主动修改）
> - 改了缩进/空格 → 归一化后相同 → **仍能命中**
> - 改了大小写 → 归一化后相同 → **仍能命中**

### 与旧版 Token 级 LCS 的对比

| 维度 | 旧版 (Token 级) | 新版 (行级) |
|------|----------------|------------|
| 比较单元 | 单个 token (`string`, `=`, `;`) | 整行 normalized text |
| DP 矩阵 | M_tokens × N_tokens（数千级） | M_lines × N_lines（数百级） |
| 命中判定 | 事后按行聚合 + 70% 阈值 | **直接命中 = 贡献** |
| 跨行泄漏 | 存在（全局 LCS 跨行匹配 token） | **不存在**（行是原子单位） |

### 示例分析：行级匹配

**AI 生成代码：**
```java
List<String> names = new ArrayList<>();
for (User u : userList) {
    names.add(u.getName());
}
```

**用户提交代码（添加了判空、改了变量名）：**
```java
// 获取用户名列表
List<String> nList = new ArrayList<>();
if (userList != null) {
    for (User usr : userList) {
        nList.add(usr.getName());
    }
}
```

*   **引擎处理**：逐行归一化后比对。`"list<string>names=newarraylist<>();"` vs `"list<string>nlist=newarraylist<>();"` — 变量名不同，**不命中**。`"}"` vs `"}"` — **命中**。
*   **结果**：仅 `}` 等完全一致的行被命中，变量名改动的行不计入。
*   **动作**：L2 根据命中行数/总非空行数计算 score，并精确产出 `exactContributedLines`。

---

## L3: AST (抽象语法树特征匹配)

**核心原理：基于 Tree-sitter 的代码语义结构分析**

AST 解析不仅看文本，更看代码的逻辑成分。它提取代码的**结构特征**（比如调用了哪些方法、定义了哪些变量、有没有 `while` 循环等），以集合（Set）的方式计算相似度（Jaccard 相似系数）。

**在系统中的作用：**
最终兜底，专门对付**"深度重构 (Deep Refactor)"**。此时文本层面已经面目全非，L1 和 L2 双双失效。

### 触发熔断条件

AST 计算极其昂贵，因此 L3 并非每次都执行。需满足：
1. **L1 和 L2 均未直接判定成功**（即处于模棱两可的状态）。
2. **新增行数少于 1000 行**（防性能爆炸）。
3. **文件后缀在白名单内**（支持 Java, TS, Python 等）。
4. **提供了全量文件上下文**（AST 需要完整的类结构才能解析）。

### 示例分析：深度重构

**AI 生成代码（传统 for 循环）：**
```java
public List<String> getActiveUserNames(List<User> users) {
    List<String> result = new ArrayList<>();
    for (User user : users) {
        if (user.getStatus() == 1) {
            result.add(user.getName());
        }
    }
    return result;
}
```

**用户提交代码（Java 8 Stream 重构）：**
```java
public List<String> fetchActiveNames(List<User> userList) {
    return userList.stream()
            .filter(u -> u.getStatus() == 1)
            .map(User::getName)
            .collect(Collectors.toList());
}
```

*   **L1/L2 失败**：代码结构完全改变，LCS 连括号和分号的顺序都对不上，文本匹配率可能低于 20%，导致 L1/L2 都认为"毫无关联"。
*   **L3 引擎介入**：
    *   **提取 AI 特征**：标识符 (`List`, `String`, `User`, `result`, `users`)，方法调用 (`getStatus`, `add`, `getName`)。
    *   **提取用户特征**：标识符 (`List`, `String`, `User`, `userList`)，方法调用 (`stream`, `filter`, `getStatus`, `map`, `getName`, `collect`, `toList`)。
*   **比对分析**：系统发现核心业务特征节点（如 `getStatus`, `getName`, `User`, `List<String>`）高度重合。
*   **结果**：特征相似度达到 `0.65`（假设阈值为 `0.60`）。
*   **动作**：引擎判定该段逻辑继承自 AI，给出 **DEEP_REFACTOR** 的定性结论。即使按文本算只有 0 行业绩，也会通过保底机制认可 AI 的业务逻辑贡献。
