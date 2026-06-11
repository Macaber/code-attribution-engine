# Normalizer — 代码规格化归一组件说明文档

[Normalizer](file:///Users/yfsun/mywork/code-attribution-engine/src/main/java/com/macaber/attribution/core/Normalizer.java) 负责代码在进入任何相似度匹配算法（Winnowing、LCS、AST）之前的预处理工作。它的核心任务是消除代码格式化、空格、大小写等不影响逻辑的“格式噪音”。

---

## 1. 核心职责 (Core Responsibilities)

* **保留注释进行比对**：**特意保留单行和多行注释**。由于 AI 自动生成的代码注释如果被用户采纳，同样代表着 AI 的贡献，因此注释也被视为待分析的源码内容，不予过滤。
* **格式噪音消除**：清除空格、回车、缩进、制表符等空白字符。
* **大小写归一**：转换为全小写。
* **物理位置映射建立 (Line Tracking)**：在去除空行后，建立“规格化后的原子行”到“原始未修改代码物理行号”的反向追溯字典（`LineMapping`），这是 L2 层能将匹配行精确映射回真实行号的基础。

---

## 2. 核心方法详解 (Methods)

### 2.1 文本规格化 (`normalizeText`)
* **用途**：用于 L1 Winnowing 算法的比对输入。
* **逻辑**：
  1. 使用正则清除全部空白字符：`code.replaceAll("\\s+", "")`。
  2. 最终文本全部转为小写。
  * **注意**：该过程故意不清除注释，注释会作为普通文本参与滑动指纹的计算。

### 2.2 行规格化与追溯建立 (`normalizeToLines`)
* **用途**：用于 L2 LCS 原子行比对。这是行追踪（Line Tracking）的关键。
* **逻辑**：
  1. 将输入代码按 `\n` 切分为多行。
  2. **空行检测**：对每一行进行去空格及转小写。若该行仅由空白字符组成，或者为空，则**直接跳过，不计入规格化列表**（即使有注释的行，只要包含了注释内容字符，即非空，便予以保留）。
  3. **建立书签映射**：对非空行，将其去除全部空格并转为小写，存入 `normalizedLines` 列表，并在 `originalLineIndices` 中记录该行在原始输入（包含空行）中的 **0 索引行号**。
  4. 拼接全部 `normalizedLines` 构成紧凑的 `normalizedText`。

---

## 3. LineMapping 数据结构 (Data Structure)

`normalizeToLines` 方法执行完毕后，会返回一个 `LineMapping` 实体。该类包含以下属性：

| 属性名 | 类型 | 说明 |
|---|---|---|
| `normalizedLines` | List\<String\> | 规格化去噪后的非空行列表（不含任何空格，全小写，保留注释内容） |
| `originalLineIndices` | List\<Integer\> | 索引书签：第 $i$ 个规格化行对应原始未去噪代码中的物理行号 |
| `normalizedText` | String | 将所有非空行无缝拼接成的一行长文本，供短文本判断使用 |
| `nonBlankLineCount` | int | 规格化非空行总行数（`normalizedLines` 列表的长度） |

### 3.1 物理位置逆向穿透追踪示例 (保留注释)
**输入原始代码（5 行）：**
```java
// 行 0 (注释)
public void run() { // 行 1
                    // 行 2 (空行)
    int a = 1;      // 行 3
}                   // 行 4
```

**经过 `normalizeToLines` 规格化处理后：**
1. 行 0：规格化为 `"//行0(注释)"`，原始行号为 `0`。
2. 行 1：规格化为 `"publicvoidrun(){//行1"`，原始行号为 `1`。
3. 过滤行 2 (纯空行)。
4. 行 3：规格化为 `"inta=1;//行3"`，原始行号为 `3`。
5. 行 4：规格化为 `"}//行4"`，原始行号为 `4`。

**生成的 `LineMapping` 属性：**
* `normalizedLines` = `["//行0(注释)", "publicvoidrun(){//行1", "inta=1;//行3", "}//行4"]`
* `originalLineIndices` = `[0, 1, 3, 4]` （规格化后的第 0 行对应原第 0 行；第 2 行对应原第 3 行...）
* `nonBlankLineCount` = `4`

*在比对算法（L2 LCS）发现规格化后的第 `0` 行（即 `"//行0(注释)"`）相同时，系统可以直接通过 `originalLineIndices.get(0)` 还原出该段代码在用户原始文件里的位置是第 `0` 行（注释行也被精确计入了 AI 贡献）。*
