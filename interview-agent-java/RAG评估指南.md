# RAG 评估指南：如何对检索系统进行离线质量评估

## 一、为什么需要 RAG 评估？

在 RAG（Retrieval-Augmented Generation）系统中，**检索质量直接决定了生成质量的上限**。如果检索阶段没有召回正确的知识文档，后续的大模型生成再强也只能"巧妇难为无米之炊"。

然而，检索质量并不是一个"跑一下看看结果对不对"就能判断的事情。我们需要：

- **量化指标**：用 Recall@K、MRR 等可对比的数字来衡量检索效果
- **可复现实验**：每次调参（换 Embedding 模型、调 BM25 参数、换 Reranker）都能在同一数据集上跑出可对比的报告
- **定位短板**：知道哪些领域（Go / MySQL / Redis）、哪些难度（easy / hard）的检索效果差，针对性优化

本项目的 RAG 离线评估系统正是为此设计的。

---

## 二、系统架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                     EvalCommandRunner (CLI 入口)                  │
├──────────────┬──────────────────────┬───────────────────────────┤
│  --prepare   │  --gen-dataset       │  默认模式（执行评估）        │
│  解析MD题库   │  自动生成评估数据集    │  加载数据集→检索→计算→报告  │
│  写入Milvus  │  基于manifest        │                           │
│  导出manifest│                      │                           │
└──────┬───────┴──────────┬───────────┴───────────┬───────────────┘
       │                  │                       │
       ▼                  ▼                       ▼
┌─────────────┐  ┌──────────────┐  ┌──────────────────────────────┐
│QuestionParser│  │  EvalSample  │  │     RetrievalEvaluator       │
│DocumentLoader│  │  (数据集样本) │  │  Milvus + BM25 双路召回       │
│  MilvusStore │  │              │  │  → ID去重 → Reranker重排      │
└─────────────┘  └──────────────┘  │  → 计算 Recall/MRR → 报告    │
                                   └──────────────────────────────┘
                                               │
                                               ▼
                                   ┌────────────────────────┐
                                   │   EvalReportRenderer    │
                                   │   JSON + Markdown 报告  │
                                   └────────────────────────┘
```

核心组件：

| 组件 | 职责 |
|------|------|
| `EvalCommandRunner` | CLI 入口，解析参数，调度三种模式 |
| `RetrievalEvaluator` | 评估流水线：检索 → 对比 → 计算指标 |
| `EvalMetrics` | 指标计算：Recall@K、MRR、分组聚合 |
| `EvalReportRenderer` | 报告渲染：输出 JSON + Markdown |
| `RagConfigSnapshot` | 配置快照：记录本次实验参数，便于 A/B 对比 |
| `RagQualityEvaluator` | 在线 LLM 评估：忠实度/相关性/完整性三维打分 |

---

## 三、评估指标详解

### 3.1 Recall@K（召回率）

$$\text{Recall@K} = \frac{|\text{前K条检索结果} \cap \text{标注相关文档}|}{|\text{标注相关文档}|}$$

**含义**：在检索返回的前 K 条结果中，命中了多少标注为相关的文档。

- **Recall@10**：前 10 条结果的召回率（核心指标，反映用户实际能看到的检索质量）
- **Recall@20**：前 20 条结果的召回率（反映检索管道的整体召回能力）

### 3.2 MRR（Mean Reciprocal Rank，平均倒数排名）

$$\text{MRR} = \frac{1}{|\text{第一个相关文档的排名}|}$$

**含义**：第一个命中的相关文档排在第几位。MRR = 1.0 表示每条查询的第一个结果就是正确答案。

### 3.3 分组维度

报告会自动按以下维度分组统计：

- **按 Topic**：Go / MySQL / Redis / 分布式系统 / 消息队列
- **按 Difficulty**：easy / medium / hard

这让你能快速定位"哪个领域、哪个难度的检索效果最差"。

---

## 四、完整操作流程

### 4.1 前置条件

确保基础设施已启动：

```bash
# 启动 Milvus + Redis + MySQL
make infra-up
# 或
docker-compose up -d
```

确认 `.env` 中配置了正确的 DashScope API Key（用于 Embedding 和 LLM 解析）。

### 4.2 Step 1：准备题库（--prepare）

将 Markdown 格式的面试题库写入 Milvus 向量库，并导出 manifest 清单文件。

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="eval --prepare"
```

**执行流程：**

1. 扫描 `data/questions/` 下所有 `<topic>_interview/<name>.md` 文件
2. 调用 LLM 解析 Markdown → 结构化题目（content / reference / difficulty / skills）
3. 为每道题生成稳定 ID（如 `eval_distributed_001`）
4. 写入 Milvus（以 `eval_user` 隔离，不影响业务数据）
5. 导出 `data/eval/manifest.json`

**输出示例：**

```
[Prepare] 找到 5 个 MD 题库文件
[Prepare] ✓ distributed_interview.md: 40 道题写入 Milvus
[Prepare] ✓ go_interview.md: 103 道题写入 Milvus
======== Prepare 完成 ========
题目总数:   280
  distributed: 40 道
  go: 103 道
  mq: 42 道
  mysql: 55 道
  redis: 40 道
```

**自定义参数：**

```bash
# 指定题库目录和 manifest 输出路径
mvn spring-boot:run -Dspring-boot.run.arguments="eval --prepare --questions data/my_questions --manifest data/eval/my_manifest.json"
```

### 4.3 Step 2：生成评估数据集（--gen-dataset）

基于 manifest 自动生成评估样本集。

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="eval --gen-dataset"
```

**执行流程：**

1. 读取 `data/eval/manifest.json`
2. 按 topic 分组，每组均匀采样（默认总共 50 条）
3. 为每条种子题生成检索 query（去前缀、去标点、截断到 35 字符）
4. 自动标注相关文档 ID（种子题本身 + 同 topic 中 skill 重叠 ≥ 2 的题目）
5. 输出 `data/eval/dataset_v1.json`

**自定义样本数量：**

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="eval --gen-dataset --sample-count 100"
```

**数据集样本格式：**

```json
{
  "id": "eval_002",
  "query": "2.1 用 Redis 怎么实现分布式锁",
  "topic": "分布式系统",
  "difficulty": "medium",
  "note": "自动生成，种子题: eval_distributed_005",
  "relevant_doc_ids": ["eval_distributed_005", "eval_distributed_009", "eval_distributed_006"]
}
```

### 4.4 Step 3：执行评估（默认模式）

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="eval --note baseline"
```

**执行流程：**

1. 加载评估数据集 `data/eval/dataset_v1.json`
2. 从 manifest 构建 BM25 内存索引
3. 逐条样本执行完整 RAG 检索：Milvus 向量召回 + BM25 关键词召回 → ID 去重 → Reranker 重排
4. 对比检索结果与标注答案，计算 Recall@10 / Recall@20 / MRR
5. 按 topic / difficulty 分组聚合
6. 输出报告到 `data/eval/reports/`

**输出示例：**

```
======== 评估完成 ========
样本数:     50
耗时:       21.07s
Recall@10:  0.7833
Recall@20:  0.8567
MRR:        1.0000
JSON 报告:     data/eval/reports/eval_report_20260614_151045.json
Markdown 报告: data/eval/reports/eval_report_20260614_151045.md
```

### 4.5 跳过 Reranker 对比

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="eval --note no-rerank --skip-rerank"
```

这会跳过 Rerank 阶段，让你对比"有 Reranker vs 无 Reranker"的效果差异。

---

## 五、A/B 对比实验

评估系统的核心设计目标之一就是支持 **A/B 对比**。操作方法：

### 5.1 实验设计

| 实验组 | 命令 | 变量 |
|--------|------|------|
| Baseline | `eval --note baseline` | 默认配置 |
| 无 Rerank | `eval --note no-rerank --skip-rerank` | 关闭 Reranker |
| 调 TopK | 设置环境变量 `EVAL_RETRIEVE_TOP_K=30` | 增大召回量 |
| 换 Embedding | 修改 `application.yml` 中的 embedding model | 换嵌入模型 |

### 5.2 配置快照

每次评估都会自动记录当前的 RAG 配置快照到报告中：

```json
{
  "embedding_model": "text-embedding-v3",
  "vector_dim": 1024,
  "vector_top_k": 20,
  "bm25_top_k": 20,
  "bm25_k1": 1.5,
  "bm25_b": 0.75,
  "reranker_type": "cross-encoder",
  "rerank_top_n": 20,
  "note": "baseline"
}
```

对比两份报告时，从配置快照就能看出"两次实验的参数差异在哪"。

### 5.3 对比要点

比较两份报告时重点关注：

1. **整体 Recall@10 变化**：核心指标，直接反映用户体感
2. **分 Topic 变化**：看哪些领域提升/退步
3. **分 Difficulty 变化**：hard 题的检索往往最难提升
4. **Worst-10 变化**：最差样本是否改善了

---

## 六、报告解读

### 6.1 Markdown 报告结构

```markdown
# RAG 离线评估报告
- 运行时间 / 数据集版本 / 样本数 / 耗时

## 1. 配置快照        ← 本次实验参数
## 2. 整体指标        ← Recall@10 / Recall@20 / MRR
## 3. 按 Topic 分组   ← 各领域表现
## 4. 按难度分组      ← 各难度表现
## 5. 异常样本        ← Worst-10，优先优化目标
```

### 6.2 指标解读参考

| 指标范围 | 质量评级 | 建议 |
|----------|----------|------|
| Recall@10 ≥ 0.85 | 优秀 | 可上线 |
| Recall@10 0.70~0.85 | 良好 | 关注 Worst 样本 |
| Recall@10 0.50~0.70 | 一般 | 需优化检索策略 |
| Recall@10 < 0.50 | 较差 | 需排查 Embedding / 分块策略 |
| MRR = 1.0 | 完美 | 每条查询第一个结果就命中 |
| MRR < 0.8 | 需关注 | 相关文档排名偏后，考虑优化 Reranker |

### 6.3 Worst-10 分析

报告最后列出 Recall@10 最低的 10 条样本，这些是优先优化目标：

- 如果某 topic 集中出现 → 该领域的题库分块或 Embedding 效果差
- 如果 hard 难度集中出现 → 复杂查询的语义理解不足，考虑 query 改写
- 如果 FirstHitRank 都 = 1 但 Recall 低 → 相关文档标注过多，检索 TopK 不够

---

## 七、在线质量评估（RagQualityEvaluator）

除了离线检索评估，系统还提供基于 LLM 的**在线质量评估**能力：

### 7.1 三维质量评估

对 RAG 系统的最终输出进行打分：

| 维度 | 含义 |
|------|------|
| Faithfulness（忠实度） | 回答是否完全基于检索到的文档，有无臆造 |
| Relevance（相关性） | 检索到的文档是否与用户问题相关 |
| Completeness（完整性） | 回答是否覆盖了问题的所有方面 |

### 7.2 题库诊断评估

评估题库检索结果与 JD（岗位描述）的匹配质量：

| 指标 | 含义 |
|------|------|
| Precision（精确率） | 检索到的题目中有多少是真正与 JD 相关的 |
| Recall（召回率） | JD 中的技能方向被题库覆盖了多少 |
| Skill Coverage | 逐项列出每个技能的覆盖状态 |

---

## 八、全部 CLI 参数速查

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--prepare` | - | 准备模式：解析 MD → 写入 Milvus → 导出 manifest |
| `--gen-dataset` | - | 生成模式：基于 manifest 自动生成评估数据集 |
| `--skip-rerank` | false | 跳过 Reranker（用于 A/B 对比） |
| `--dataset <path>` | `data/eval/dataset_v1.json` | 评估数据集路径 |
| `--out <dir>` | `data/eval/reports` | 报告输出目录 |
| `--note <text>` | "" | 实验备注（写入报告） |
| `--questions <dir>` | `data/questions` | 题库 MD 文件目录 |
| `--manifest <path>` | `data/eval/manifest.json` | Manifest 文件路径 |
| `--sample-count <n>` | 50 | 每个 topic 的采样数量 |

**环境变量：**

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `EVAL_RETRIEVE_TOP_K` | 20 | 评估时 Milvus + Rerank 的 TopK |

---

## 九、最佳实践

### 9.1 评估工作流

```
1. 修改 RAG 配置（换模型 / 调参数 / 改分块策略）
2. 运行 eval --note "实验描述"
3. 打开 Markdown 报告，对比上一次结果
4. 分析 Worst-10，找到优化方向
5. 重复 1-4 直到满意
```

### 9.2 注意事项

- **评估不影响业务数据**：评估使用独立的 `eval_user`，与真实用户数据完全隔离
- **BM25 是内存索引**：每次评估启动时从 manifest 重新构建，无需持久化
- **数据集版本管理**：修改数据集后建议更新版本号（dataset_v2.json），便于追溯
- **CLI 执行完即退出**：eval 子命令执行完毕后自动 `System.exit()`，不会保持 Web 服务运行
- **非 eval 启动无影响**：正常启动 Web 服务时，EvalCommandRunner 检测到第一个参数不是 `eval` 会直接跳过

### 9.3 典型优化方向

| 问题现象 | 可能原因 | 优化方向 |
|----------|----------|----------|
| 某 topic Recall 显著低于其他 | 该领域 Embedding 语义表达差 | 换 Embedding 模型 / 增加同义词 |
| hard 题 Recall 低 | 复杂查询关键词分散 | Query 改写 / 增加 BM25 权重 |
| MRR < 1.0 | 相关文档排名不在第一 | 优化 Reranker / 调整 Rerank TopN |
| Recall@10 低但 Recall@20 高 | 相关文档在 11-20 名 | 增大 Rerank 输入量 / 优化排序 |
| 所有指标都低 | 题库未正确入库 | 检查 prepare 日志 / Milvus 连接 |

---

## 十、快速上手（TL;DR）

```bash
# 0. 启动基础设施
make infra-up

# 1. 解析题库 → 写入向量库
mvn spring-boot:run -Dspring-boot.run.arguments="eval --prepare"

# 2. 生成评估数据集
mvn spring-boot:run -Dspring-boot.run.arguments="eval --gen-dataset"

# 3. 跑一次 baseline
mvn spring-boot:run -Dspring-boot.run.arguments="eval --note baseline"

# 4. 对比：跳过 Reranker
mvn spring-boot:run -Dspring-boot.run.arguments="eval --note no-rerank --skip-rerank"

# 5. 查看报告
# data/eval/reports/eval_report_*.md
```

三步走：**Prepare → GenDataset → Eval**，即可得到一份完整的 RAG 检索质量评估报告。
