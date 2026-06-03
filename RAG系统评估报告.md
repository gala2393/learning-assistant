# RAG 知识检索问答系统 — 评估报告

> 评估时间：2026-05-29
> 项目：learning-assistant
> 评估范围：后端 RAG 管线全链路（文档摄入 → 分块 → Embedding → 检索 → 生成）

---

## 一、当前系统架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                        文档摄入流程                              │
│                                                                 │
│  上传/导入 → 格式解析(PDF/DOCX/PPTX/TXT/MD/HTML)                │
│    → 语义分块(300~800字符, 100字符重叠)                          │
│    → Embedding(text-embedding-v3, OpenAI兼容API)                │
│    → 存储(MySQL: chunk文本 + embedding JSON + 元数据)           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        问答检索流程                              │
│                                                                 │
│  用户问题 → 意图检测(正则:定义/比较/用途/出处)                    │
│    → 混合检索:                                                  │
│       1. 关键词匹配 (意图驱动, 精确文本匹配)                     │
│       2. 当前页上下文 (阅读位置关联)                             │
│       3. 向量相似度 (余弦相似度, 阈值0.55)                       │
│       4. BM25兜底 (中文n-gram, K1=1.5, B=0.75)                  │
│       5. 概览模式 (前5个chunk)                                  │
│    → 上下文组装 (最大3500字符, 含图片标记)                       │
│    → LLM生成 (OpenAI/Anthropic兼容API, SSE流式)                 │
│    → 答案装饰 (来源引用 + 格式清洗)                             │
│    → 持久化 (rag_question + rag_question_source)                │
└─────────────────────────────────────────────────────────────────┘
```

### 关键配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `EMBEDDING_ENABLED` | `false` | Embedding 服务开关 |
| `EMBEDDING_MODEL` | `text-embedding-v3` | Embedding 模型 |
| `EMBEDDING_TOP_K` | `5` | 返回最相关 chunk 数量 |
| `EMBEDDING_SCORE_THRESHOLD` | `0.55` | 余弦相似度最低阈值 |
| `LLM_ENABLED` | `false` | LLM 服务开关 |
| `CHUNK_MIN_SIZE` | `300` | 分块最小字符数 |
| `CHUNK_MAX_SIZE` | `800` | 分块最大字符数 |
| `CHUNK_OVERLAP` | `100` | 分块重叠字符数 |
| 上下文上限 | `3500` 字符 | 送给 LLM 的最大上下文量 |
| 图片上限 | `5` 张/请求 | 每次请求最多附带图片数 |

---

## 二、当前系统优点

### 2.1 混合检索策略设计合理

系统实现了多层回退机制，覆盖了不同场景：

- **关键词匹配**：对"什么是X"、"X和Y的区别"等结构化问题效果好
- **当前页上下文**：结合用户阅读位置，适合"这段讲了什么"类问题
- **向量相似度**：语义级别的模糊匹配
- **BM25 兜底**：向量服务不可用时仍能工作
- **概览模式**：对"这本书讲什么"类问题有专门处理

### 2.2 中文支持较好

- BM25 实现了中文字符 n-gram 分词（2-6字），对中文检索有效
- 意图检测覆盖了中文常见句式（什么是/有什么用/区别/在哪里提到）
- 停用词过滤包含英文常用词
- LLM 提示词强制中文回答

### 2.3 工程健壮性

- **流式输出（SSE）**：用户体验好，有"搜索中"状态反馈
- **多格式解析**：PDF/DOCX/PPTX/TXT/MD/HTML 全覆盖
- **PDF OCR 兜底**：扫描件也能处理（需 Tesseract）
- **LLM 降级**：服务不可用时直接引用原文回答，保证可用性
- **分片上传**：大文件支持分片上传 + 校验和验证
- **会话管理**：对话历史持久化、收藏、固定、重命名

### 2.4 数据安全

- 按用户隔离数据（ownerId 过滤）
- 文件名安全过滤（`sanitizeFileName`）
- 路径遍历防护（`resolveStoredPath` 的 remap 机制）

---

## 三、当前存在的问题

### 3.1 性能瓶颈（严重）

#### 问题：向量检索 = 全表扫描

**位置**：`RagService.java:1314-1342` — `findVectorScoredChunks()`

```java
// 当前实现：从 MySQL 加载所有 chunk，内存中逐个计算余弦相似度
for (LearningMaterialEntity material : materials) {
    for (MaterialChunkEntity chunk : materialChunkRepository
            .findByMaterialIdOrderByChunkIndexAsc(material.getId())) {
        List<Double> chunkEmbedding = parseEmbedding(chunk.getEmbeddingJson());
        double score = cosineSimilarity(questionEmbedding.get(), chunkEmbedding);
        // ...
    }
}
```

**影响分析**：

| chunk 数量 | 预估响应时间 | 体验 |
|-----------|------------|------|
| 100 | <100ms | 可接受 |
| 1,000 | ~500ms | 可感知延迟 |
| 10,000 | ~5s | 卡顿 |
| 100,000 | ~30s+ | 不可用 |

每个 chunk 需要：
1. 从 MySQL 读取 `embedding_json`（TEXT 类型，可能 6KB+）
2. JSON 解析为 `List<Double>`（1536维向量）
3. 1536 次乘法 + 1536 次加法 + 开方运算

**还有**：`RagService.java:1393` 每次解析 embedding 都 `new ObjectMapper()`，Jackson 的 ObjectMapper 是重量级对象，应复用。

#### 问题：BM25 每次重建索引

**位置**：`RagService.java:1281-1312` — `findScoredChunks()`

每次查询都执行：
```java
Bm25Scorer scorer = new Bm25Scorer(allChunkData); // 重建倒排索引
```

没有按 materialId 缓存索引，重复计算浪费资源。

#### 问题：chunk 查询重复执行

在 `findScoredChunks()` 中，同一个 material 的 chunk 被查询了两次：
- 第 1294 行：构建 allChunkData 时查一次
- 第 1305 行：计算分数时又查一次

### 3.2 检索质量

#### 问题：上下文窗口太小

**位置**：`RagService.java:1371-1386` — `limitContextChunks()`

```java
if (!limited.isEmpty() && totalChars + text.length() > 3500) {
    break; // 3500字符就截断
}
```

3500 字符约等于 1750 个中文字，对于复杂问题（比如"请详细解释TCP三次握手的过程"），这些上下文远远不够。现代 LLM（GPT-4、Claude）支持 128K+ 上下文，应该充分利用。

#### 问题：意图检测依赖正则，覆盖率有限

**位置**：`RagService.java:338-424`

当前正则覆盖的句式：
- ✅ "什么是TCP"、"TCP的定义"
- ✅ "A和B有什么区别"
- ✅ "X在哪里提到"
- ❌ "聊聊TCP/IP协议"（自然语言）
- ❌ "介绍一下Spring的IoC"（介绍类）
- ❌ "TCP是怎么工作的"（原理类）
- ❌ "为什么需要三次握手"（原因类）
- ❌ "TCP和UDP哪个更好"（判断类）

正则模式越多越脆弱，稍有变化就匹配不上。

#### 问题：向量检索阈值固定

`scoreThreshold = 0.55` 是硬编码的，但不同 embedding 模型、不同领域、不同长度的问题，合适的阈值不同。短问题和长文档的相似度分布差异很大。

#### 问题：多轮对话没有查询改写

**位置**：`RagService.java:685-699` — `buildQuestionWithHistory()`

```java
// 只是简单拼接历史
sb.append(msg.role()).append("：").append(msg.content()).append("\n");
sb.append("\n当前问题：").append(question);
```

用户问"那它的优缺点呢？"，其中"它"指代上一轮讨论的概念。直接拿"那它的优缺点呢？"去做向量检索，效果会很差。

### 3.3 分块策略

#### 问题：分块偏简单

**位置**：`MaterialService.java:1495-1604` — `semanticChunk()`

当前逻辑：
- 按 `\n\n` 和 `\n` 拆段落
- 累积到 300~800 字符为一块
- 超过 800 字的段落按句子（`。！？!?.;；`）拆分
- 块之间加 100 字符重叠

问题：
1. 很多文档段落分隔不规范（只有 `\n` 没有 `\n\n`），`splitParagraphs` 实际上会把每行当作一个段落
2. 没有考虑**语义完整性**——一个概念的定义和解释可能被切到两个 chunk
3. overlap 只有 100 字符，对于中文可能只有 50 个字，上下文衔接不够
4. 没有按 token 数计算，不同内容密度差异大

#### 问题：chunk 没有摘要/标题

每个 chunk 只有原始文本，没有自动生成的摘要或关键词。向量检索时如果 query 和原文用词差异大（比如用户问"内存管理"，原文说"存储器分配"），就匹配不上。

### 3.4 输出处理

#### 问题：Markdown 清洗过度

**位置**：`RagService.java:1207-1220` — `cleanAnswerText()`

```java
return content.trim()
    .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")  // 去掉加粗
    .replaceAll("\\*([^*\\n]+)\\*", "$1")      // 去掉斜体
    .replaceAll("(?m)^\\s*\\*\\s+", "")         // 去掉列表标记
    .replaceAll("(?m)^\\s*-\\s+", "")           // 去掉列表标记
    .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")  // 去掉标题标记
    .replace("*", "");                           // 去掉所有星号
```

如果 LLM 返回了合理的结构化回答（带列表、标题），这些全被打散成纯文本，可读性变差。

#### 问题：装饰逻辑冗余

`decorateAnswer()` 方法中有多处重复逻辑：
- 第 1150 行检查答案是否已包含"书本依据"/"原文依据"
- 第 1135 行有一个乱码字符串 `"鏈敓鎴愭湁鏁堝洖绛斻€?"`（看起来是编码问题）

### 3.5 前端相关

（基于代码阅读，未实际运行测试）

- 会话状态用 `sessionStorage` + `localStorage` 持久化，刷新页面可能丢失进行中的流式响应
- 没有看到检索结果的可视化（哪些 chunk 被选中了、分数多少）
- 建议问题（`suggest-questions`）功能依赖 LLM，延迟可能较高

---

## 四、距离生产级 RAG 还差什么

### 4.1 向量数据库（最关键差距）

**现状**：MySQL TEXT 列存 embedding，内存暴力搜索

**生产方案**：

| 方案 | 特点 | 推荐场景 |
|------|------|---------|
| **pgvector** | PostgreSQL 扩展，支持 HNSW/IVF 索引，与现有 MySQL 可共存 | 如果可引入 PostgreSQL |
| **Qdrant** | Rust 实现，高性能，支持过滤，Docker 部署简单 | 独立向量服务 |
| **Milvus** | 功能最全，支持标量+向量混合查询 | 大规模场景 |
| **Chroma** | 最轻量，嵌入式，适合原型 | 快速验证 |

**收益**：
- 检索从 O(n) 降到 O(log n) 或近似 O(1)
- 支持百万级 chunk
- 原生支持向量 + 标量混合过滤

### 4.2 Reranking（重排序）

**现状**：向量检索直接取 top-K

**标准做法**：
```
向量检索召回 top-30
    → Cross-Encoder Reranker 精排
    → 取 top-5 送给 LLM
```

**推荐模型**：
- `bge-reranker-v2-m3`（开源，支持中英文）
- `Cohere Rerank`（API 服务）
- `Jina Reranker`（API 服务）

**收益**：显著提升检索准确率，尤其是消除向量检索中的"语义相近但不相关"问题。

### 4.3 查询改写 / 查询扩展

**现状**：直接用原始问题检索

**可以加的能力**：

1. **HyDE（假设性文档嵌入）**
   ```
   原始问题: "TCP三次握手是什么"
   → LLM 生成假设答案: "TCP三次握手是建立TCP连接时客户端和服务端交换三个报文段的过程..."
   → 用假设答案的 embedding 去检索（更接近文档风格）
   ```

2. **查询改写**
   ```
   原始问题: "它有什么优缺点"
   → 改写: "TCP协议的优缺点是什么"
   ```

3. **多查询融合**
   ```
   原始问题: "TCP是什么"
   → 生成变体: ["TCP协议定义", "传输控制协议介绍", "TCP工作原理"]
   → 分别检索，合并去重
   ```

### 4.4 分层索引

**现状**：扁平的 chunk 列表

**更好的做法**：
```
文档
├── 文档摘要 (全局关键词、主题)
├── 第1章摘要
│   ├── 1.1节 chunk_1
│   ├── 1.1节 chunk_2
│   └── 1.2节 chunk_3
├── 第2章摘要
│   └── ...
```

查询时：
1. 先在文档/章节摘要层定位
2. 再在目标章节内细粒度检索

**收益**：长文档检索更精准，避免无关章节的 chunk 干扰。

### 4.5 多轮对话增强

**现状**：简单拼接历史消息

**需要的能力**：
- 指代消解（"它"→"TCP协议"）
- 对话式查询改写
- 上下文感知的检索（考虑前几轮已讨论的内容）

### 4.6 评估与反馈

**现状**：没有质量评估机制

**需要建立**：
- **Answer Faithfulness**：回答是否忠实于检索片段（防止幻觉）
- **Context Relevance**：检索片段是否与问题相关
- **用户反馈**：点赞/点踩，用于持续优化
- **离线评估集**：建立标准 QA 对，定期跑回归测试

### 4.7 缓存层

**缺失的缓存**：
- Embedding 缓存：相同文本不需要重复调用 API
- BM25 索引缓存：按 materialId 缓存，文档更新时失效
- 查询结果缓存：相同问题 + 相同文档可以复用结果

---

## 五、改进建议路线图

### 第一阶段：低成本高收益（1-2 周）

| # | 改进项 | 文件 | 难度 |
|---|--------|------|------|
| 1 | 去掉 `cleanAnswerText` 的 Markdown 破坏，改为保留合理格式 | `RagService.java:1207` | 低 |
| 2 | 修复 `parseEmbedding` 中重复创建 ObjectMapper 的问题 | `RagService.java:1393` | 低 |
| 3 | 扩大上下文窗口到 8000-12000 字符 | `RagService.java:1379` | 低 |
| 4 | BM25 索引按 materialId 缓存 | `RagService.java:1281` | 低 |
| 5 | 去掉 chunk 重复查询 | `RagService.java:1294-1305` | 低 |
| 6 | 修复乱码字符串 | `RagService.java:1135` | 低 |
| 7 | Embedding 结果缓存（Caffeine/Redis） | `MaterialService.java:1907` | 中 |

### 第二阶段：检索质量提升（2-4 周）

| # | 改进项 | 难度 |
|---|--------|------|
| 8 | 引入 pgvector 或 Qdrant 替代 MySQL 暴力搜索 | 中 |
| 9 | 添加 Reranker 精排（bge-reranker-v2-m3） | 中 |
| 10 | 实现查询改写（多轮对话指代消解） | 中 |
| 11 | 优化分块策略（按句子语义边界切分，增大 overlap） | 中 |
| 12 | 为 chunk 添加自动生成的摘要/关键词 | 中 |

### 第三阶段：生产级能力（4-8 周）

| # | 改进项 | 难度 |
|---|--------|------|
| 13 | 实现 HyDE / 多查询检索 | 中 |
| 14 | 分层索引（文档摘要 → 章节摘要 → chunk） | 高 |
| 15 | 检索质量评估框架（faithfulness + relevance） | 中 |
| 16 | 用户反馈闭环（点赞/点踩 → 数据标注 → 优化阈值） | 中 |
| 17 | 动态阈值（根据 query 长度和领域自适应调整） | 中 |
| 18 | 混合检索分数归一化 + 加权融合（当前是各自独立） | 中 |

---

## 六、关键代码位置速查

| 功能 | 文件 | 关键方法/行号 |
|------|------|-------------|
| 向量检索 | `RagService.java` | `findVectorScoredChunks()` :1314 |
| BM25 检索 | `RagService.java` | `findScoredChunks()` :1281 |
| BM25 实现 | `Bm25Scorer.java` | `score()` :52 |
| 意图检测 | `RagService.java` | `extractKeywordQuery()` :338 |
| 分块逻辑 | `MaterialService.java` | `semanticChunk()` :1506 |
| Embedding 生成 | `MaterialService.java` | `toEmbeddingJson()` :1907 |
| Embedding 客户端 | `OpenAiCompatibleEmbeddingClient.java` | `embed()` :35 |
| LLM 提示词 | `ThirdPartyLlmClient.java` | `answer()` :30 |
| 答案清洗 | `RagService.java` | `cleanAnswerText()` :1207 |
| 答案装饰 | `RagService.java` | `decorateAnswer()` :1130 |
| 上下文限制 | `RagService.java` | `limitContextChunks()` :1371 |
| 流式问答 | `RagService.java` | `chatStream()` :177 |
| 多轮历史 | `RagService.java` | `buildQuestionWithHistory()` :685 |
| 余弦相似度 | `RagService.java` | `cosineSimilarity()` :1410 |
| PDF 解析 | `MaterialService.java` | `parsePdf()` :1118 |
| DOCX 解析 | `MaterialService.java` | `parseWord()` :1291 |
| PPTX 解析 | `MaterialService.java` | `parsePowerPoint()` :1364 |

---

## 七、原始基线总结

本节是 2026-05-29 改造前的原始基线结论，用于说明当时的差距来源。当前最新验收状态以第八章为准。

改造前系统是一个**功能完整的 RAG 原型**，核心流程（文档解析 → 分块 → Embedding → 检索 → LLM 生成）已经跑通，混合检索策略和中文支持做得不错。

但距离**生产级 RAG** 最关键的三个差距是：

1. **没有向量数据库** — MySQL 暴力搜索是最大性能瓶颈，数据量大了就不可用
2. **没有 Reranking + 查询改写** — 检索准确率有天花板
3. **分块和上下文策略偏保守** — 限制了回答质量

这些差距已在第八章逐项验收。

---

## 八、2026-05-29 实施与验收状态

本节记录本轮按报告路线图完成后的实际状态。上文第三、四、五节保留为原始评估基线；以下内容是当前代码的最新验收结论。

### 8.1 已完成的 RAG 改进

| 原问题/路线图项 | 当前状态 | 关键落点 |
|---|---|---|
| Markdown 清洗过度 | 已修复 | `RagService.cleanAnswerText()` 保留合理 Markdown 结构 |
| `ObjectMapper` 重复创建 | 已修复 | `RagService` 复用静态 `OBJECT_MAPPER` |
| 上下文窗口过小 | 已修复 | 上下文上限提升到 `10_000` 字符 |
| BM25 每次重建索引 | 已修复 | 按用户、材料和 chunk 版本缓存 BM25 索引 |
| chunk 查询重复 | 已优化 | 检索阶段复用已加载 chunk 数据 |
| 乱码兜底文本 | 已修复 | 回答装饰逻辑已清理 |
| Embedding 重复调用 | 已优化 | `MaterialService` 增加文本级 embedding JSON 缓存 |
| 向量数据库缺失 | 已接入 Qdrant 客户端与部署材料 | `vector` 包、`docker-compose.qdrant.yml`、`verify-qdrant.ps1` |
| Reranking 缺失 | 已实现 | 本地启发式 reranker + 外部 API reranker 配置 |
| 查询改写/多轮指代 | 已实现 | `rewriteQuestionForRetrieval()` |
| 查询扩展/HyDE | 已实现 | `QueryExpansionProperties`、多查询融合、HyDE 检索 |
| 分块策略偏简单 | 已优化 | 增大 overlap，补充 `summary`、`keywords`、`hierarchyPath` |
| 动态阈值 | 已实现 | `effectiveEmbeddingScoreThreshold()` |
| 混合分数融合 | 已实现 | 向量、BM25、关键词分数归一化加权融合 |
| 评估与反馈缺失 | 已实现 | 反馈、单条评估、离线评估集、保存评估套件、定时回归 |
| 前端评估工作台缺失 | 已实现 | `EvaluationPage.tsx`、路由和侧边栏入口 |
| 生产数据库迁移缺失 | 已实现 | Flyway baseline schema + Hibernate validate 验证 |

### 8.2 新增生产化能力

- **Qdrant 向量库路径**：支持按 `userId`、`materialId` 过滤的向量 upsert、delete、search；Qdrant 不可用时回退到 MySQL embedding 暴力检索，保证系统可用。
- **Reranker 路径**：支持本地启发式精排；配置外部 reranker API 后可切换到服务端 cross-encoder/兼容 API 精排。
- **RAG 回归评估**：支持保存评估套件、运行历史、单例运行、定时回归、faithfulness/context relevance/overall 分数。
- **反馈闭环**：支持对历史问答提交反馈，并持久化到后端。
- **迁移验收**：生产默认 `ddl-auto=validate`，Flyway 负责建表；测试环境仍使用 `create-drop`，避免影响本地测试速度。

### 8.3 本轮已通过的测试

| 验证项 | 命令 | 结果 |
|---|---|---|
| 后端全量测试 | `cd backend; .\mvnw.cmd test` | 通过：113 tests, 0 failures, 0 errors, 1 skipped |
| 前端生产构建 | `cd frontend; npm.cmd run build` | 通过：TypeScript + Vite build 成功 |
| Flyway + Hibernate validate | `.\mvnw.cmd -Dtest=FlywaySchemaValidationTest test` | 通过：验证 baseline schema 与 JPA 实体匹配 |
| Qdrant 部署材料检查 | `VectorStoreDeploymentArtifactsTest` | 通过：compose、验证脚本、生产检查文档存在且包含关键配置 |
| Qdrant 真实服务验收 | `docker compose -f docker-compose.qdrant.yml up -d` + `powershell -ExecutionPolicy Bypass -File .\tools\verify-qdrant.ps1 -BaseUrl http://127.0.0.1:6333 -Collection learning_assistant_chunks -Probe` | 通过：临时集合创建、向量写入、带过滤检索、清理全部成功 |

前端构建仅保留 Vite 的大 chunk 提示：`dist/assets/index-*.js` 超过 500 kB。这是性能优化建议，不影响构建正确性。

### 8.3.1 路线图逐项验收

| # | 原路线图项 | 当前状态 | 验收证据 |
|---|---|---|---|
| 1 | 保留 Markdown 合理格式 | 已完成 | `RagService.cleanAnswerText()` 已调整，后端全量测试通过 |
| 2 | 复用 `ObjectMapper` | 已完成 | `RagService.OBJECT_MAPPER`，后端全量测试通过 |
| 3 | 上下文窗口扩大到 8000-12000 字符 | 已完成 | `MAX_CONTEXT_CHARS = 10_000` |
| 4 | BM25 索引按 materialId 缓存 | 已完成 | `bm25IndexCache` 与缓存 key |
| 5 | 去掉 chunk 重复查询 | 已完成 | 检索阶段复用 `MaterialChunks` 数据 |
| 6 | 修复乱码字符串 | 已完成 | 回答装饰兜底文本已清理 |
| 7 | Embedding 结果缓存 | 已完成 | `MaterialService` 文本级 embedding JSON 缓存 |
| 8 | 引入 Qdrant 替代 MySQL 暴力搜索 | 已完成 | Qdrant 客户端、compose、真实容器 CRUD 探针通过 |
| 9 | 添加 Reranker 精排 | 已完成 | 本地启发式 reranker 与外部 API reranker，相关测试通过 |
| 10 | 实现查询改写 | 已完成 | `rewriteQuestionForRetrieval()` |
| 11 | 优化分块策略 | 已完成 | 增大 overlap，补充分块元数据，材料相关测试通过 |
| 12 | chunk 摘要/关键词 | 已完成 | `summary`、`keywords` 字段与生成逻辑 |
| 13 | HyDE / 多查询检索 | 已完成 | `hydeRetrievalQuery()` 与 query expansion 配置 |
| 14 | 分层索引 | 已完成 | 文档摘要层先定位候选材料，`hierarchyPath`/chunk 元数据参与细粒度检索；`RagApiTest#chatUsesMaterialSummaryAsHierarchicalSeedBeforeChunkRetrieval` 通过 |
| 15 | 检索质量评估框架 | 已完成 | faithfulness、context relevance、overall 评估 API 和测试 |
| 16 | 用户反馈闭环 | 已完成 | `PATCH /api/rag/history/{id}/feedback` 与反馈持久化 |
| 17 | 动态阈值 | 已完成 | `effectiveEmbeddingScoreThreshold()` |
| 18 | 混合检索分数归一化 + 加权融合 | 已完成 | `fuseAndRerankChunks()` |

### 8.4 外部环境验收

2026-06-01 已完成 Docker/Qdrant 真实服务验收：

- `docker --version`：`Docker version 29.5.2`
- `docker compose version`：`Docker Compose version v5.1.3`
- `docker info`：Docker Desktop Linux daemon 正常运行
- `docker compose -f docker-compose.qdrant.yml up -d`：`learning-assistant-qdrant` 容器启动成功
- `verify-qdrant.ps1 -Probe`：临时集合创建、向量 upsert、带 `userId` 过滤的向量 search、临时集合删除全部成功，检索分数 `0.9999998`

Qdrant 官方 Docker Hub 直连在当前网络下不可用，实际验收使用镜像源拉取同 digest 镜像后重新标记为 `qdrant/qdrant:v1.12.6`：

```text
sha256:89d9d35757eb57c62973c193c96b5b50ca73bbaff7fa282ef81f7fd155a3747d
```

生产/本地启用 Qdrant 时使用：

```env
VECTOR_STORE_ENABLED=true
VECTOR_STORE_PROVIDER=qdrant
VECTOR_STORE_BASE_URL=http://127.0.0.1:6333
VECTOR_STORE_COLLECTION=learning_assistant_chunks
```

### 8.5 当前结论

从代码能力和自动化测试看，RAG 已从“功能完整原型”推进到“具备生产化架构基础”的状态：检索性能路径、精排、查询扩展、分层索引、评估反馈、定时回归、迁移校验和前端评估工作台都已经补齐。

按当前报告列出的核心问题和路线图验收，RAG 后端生产化主链路已经补齐并通过自动化验证；外部 Qdrant 服务也已经完成真实容器级 CRUD 验收。剩余事项属于后续优化，不阻塞本轮报告目标：

1. 前端 bundle 拆分，降低 `index-*.js` 大 chunk 警告。
2. 更完整的浏览器级 UI 回归，覆盖评估工作台的真实交互流程。
3. 如果生产环境使用远程 Qdrant，需要在目标网络环境重复运行 `verify-qdrant.ps1 -Probe`。
