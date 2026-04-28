# Code Attribution Engine: 核心归因算法解析

代码归因引擎（Code Attribution Engine）采用了一种**漏斗式（Funnel-style）三层短路算法架构**，旨在平衡“高吞吐性能”与“高精准度”。

整体架构从轻到重分为三层：
1. **L1 Winnowing** (K-gram 滚动哈希指纹匹配)
2. **L2 LCS** (最长公共子序列字符匹配)
3. **L3 AST** (抽象语法树结构特征匹配)

引擎会根据上一层的计算得分，决定是立即短路返回（Fast-Pass / Fast-Fail），还是继续下沉到更重的一层进行深度比对。

---

## L1: Winnowing (指纹匹配)

**核心原理：基于 K-gram 的文档指纹技术**

Winnowing 将去噪后的代码切割成长度为 `k` 的滑动窗口（k-gram），并计算每个窗口的 Hash 值。在一个定义好的窗口大小（Window Size）内，只选取最小的 Hash 值作为该代码的“局部指纹”。

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

**核心原理：基于动态规划的全局字符序列比对**

LCS 寻找的是两个字符串中，保持相对顺序不变的最长字符子串。它允许中间有断层，因此对修改变量名、插入新代码等行为具有极强的鲁棒性。

**在系统中的作用：**
作为中坚力量，专门对付**“修改了变量名、调整了格式、但主体骨架依然保留”**的抄袭/搬运行为。同时，L2 承担了**精准计算行级贡献（exactContributedLines）**的重任。

### 核心机制：字符级溯源映射 (Character-to-Line Mapping)

为了把长字符串匹配的结果还原为“代码行数”，系统在去噪拼接时维护了一本“身世字典”。

**处理示例：**
```java
0: String myVal = imageService.createUuid();
1: return myVal;
```

**1. 去噪与映射：**
系统去除所有空格和换行并转为小写（**注意：系统会特意保留注释**，因为如果用户采纳了 AI 写的注释，也算作 AI 的贡献），得到长字符串：
`stringmyval=imageservice.createuuid();returnmyval;` (共 50 字符)

同时生成字典 `charToLineMap`，记录这 50 个字符分别属于哪一行：
`[0, 0, ..., 0, 1, 1, ..., 1]` （前 38 个是 `0`，后 12 个是 `1`）。

**2. LCS 匹配：**
假设原 AI 代码是 `String id1 = sitImageInspectBizImpl.generateUniqueId(); return id1;`
LCS 在这两个长字符串间寻找相同顺序的散碎字符。
对于第 0 行，找到了 `string`, `=`, `image`, `e`, `u`, `i`, `d`, `();` 等字符。

**3. 行级判定计算：**
算法统计发现：第 0 行的 38 个字符中，有 23 个字符被 LCS 命中了。
*匹配率* = `23 / 38 = 60.5%`

> **注意：** 系统采用了 **“70% 单行门槛”+“非0即1”** 的判定逻辑。
因为 `60.5% < 70%`，所以这行被判定为用户修改过大，**贡献行数计为 0**。
如果该行匹配率达到 `70%` 以上，则会被判定为 AI 贡献，**直接计为 1 行**。
设定 `70%` 阈值的目的是防止“全局 LCS 字符泄漏”（避免其他不相关行里恰巧重合的零散字母污染了判定）。

### 示例分析：L2 的容忍度

**AI 生成代码：**
```java
List<String> names = new ArrayList<>();
for (User u : userList) {
    names.add(u.getName());
}
```

**用户提交代码（添加了判空和注释）：**
```java
// 获取用户名列表
List<String> nList = new ArrayList<>();
if (userList != null) {
    for (User usr : userList) {
        nList.add(usr.getName());
    }
}
```

*   **引擎处理**：LCS 会跨越 `if (userList != null)` 这一整行，完美匹配前后两端的 `list<string>...=newarraylist<>();` 和 `for(user...:userlist){...add(...getname());}` 骨架。
*   **结果**：AI 原始的 4 行代码在字符级的 LCS 命中率极高（>80%）。
*   **动作**：L2 触发 `FUZZY` 级别，并精确计算出对应的归因行数（如 3 行或 4 行，视单行 70% 门槛而定）。

---

## L3: AST (抽象语法树特征匹配)

**核心原理：基于 Tree-sitter 的代码语义结构分析**

AST 解析不仅看文本，更看代码的逻辑成分。它提取代码的**结构特征**（比如调用了哪些方法、定义了哪些变量、有没有 `while` 循环等），以集合（Set）的方式计算相似度（Jaccard 相似系数）。

**在系统中的作用：**
最终兜底，专门对付**“深度重构 (Deep Refactor)”**。此时文本层面已经面目全非，L1 和 L2 双双失效。

### 触发熔断条件

AST 计算极其昂贵，因此 L3 并非每次都执行。需满足：
1. **L1 和 L2 均未直接判定成功**（即处于模棱两可的状态）。
2. **新增行数少于 200 行**（防性能爆炸）。
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

*   **L1/L2 失败**：代码结构完全改变，LCS 连括号和分号的顺序都对不上，文本匹配率可能低于 20%，导致 L1/L2 都认为“毫无关联”。
*   **L3 引擎介入**：
    *   **提取 AI 特征**：标识符 (`List`, `String`, `User`, `result`, `users`)，方法调用 (`getStatus`, `add`, `getName`)。
    *   **提取用户特征**：标识符 (`List`, `String`, `User`, `userList`)，方法调用 (`stream`, `filter`, `getStatus`, `map`, `getName`, `collect`, `toList`)。
*   **比对分析**：系统发现核心业务特征节点（如 `getStatus`, `getName`, `User`, `List<String>`）高度重合。
*   **结果**：特征相似度达到 `0.65`（假设阈值为 `0.60`）。
*   **动作**：引擎判定该段逻辑继承自 AI，给出 **DEEP_REFACTOR** 的定性结论。即使按文本算只有 0 行业绩，也会通过保底机制认可 AI 的业务逻辑贡献。
