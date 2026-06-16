# RAG 检索召回效果差 — 对比 Dify 分析报告

## 确认的问题清单

### P0：分数阈值在融合前过滤

**位置**：`KnowledgeBaseRecallDomainService.java` → `filterDenseCandidates` / `trimSparseCandidates`

**问题**：dense 和 sparse 两路结果在融合/rerank 之前就做了阈值过滤，导致"一路分数低但另一路分数高"的候选被提前丢弃。

**Dify 做法**：hybrid 两路结果全部收集 → 交给 DataPostProcessor（rerank） → 最后才用 score_threshold 过滤。

**修复方案**：将 `filterDenseCandidates` 和 `trimSparseCandidates` 中的阈值过滤移到融合/rerank 之后。

---

### P1：`mergeForRerank` 中 dense/sparse 分数量纲不一致导致代表选择失真

**位置**：`CandidateSet.java` → `mergeForRerank` → `preferHigherFinalScore`

**问题**：dense 的 `finalScore` 是 IP 分数（通常 0.3-0.9），sparse 的 `finalScore` 是 BM25 分数（可能在 0.01-20.0）。用 `finalScore` 直接比较选代表，sparse 分数高的 chunk 几乎总是赢，导致 dense 分数信息丢失。

**影响**：rerank 本身重新打分不受影响，但如果后续代码依赖 `finalScore` 做排序或阈值判断，就会出现量纲混乱。单库 hybrid 时如果 KB 级 rerank 因故障降级，排序就会失准。

---

### P1：单知识库时跳过全局 rerank 可能不合理

**位置**：`RetrievalExecutionService.java:121-123`

**问题**：当只有一个知识库时直接跳过全局 rerank，按 `finalScore` 排序截断。如果 KB 级 rerank 因故障降级，`finalScore` 会是 dense/sparse 原始分的较大者，量纲混乱导致排序不准。

---

### P2：`candidateK=20` + `topK=5` 可能引入噪声

**位置**：`KnowledgeBaseRecallPolicy.routeCandidateK()` = `Math.max(candidateK, resolvedTopK())` = 20

**问题**：每路从 Milvus 取 20 条候选，大量低相关候选进入 rerank。rerank 模型输入过多低相关候选时可能给出偶然高分。Dify 默认每库只取 2 条。

**建议**：candidateK 降到 5-10 试试效果。

---

## 已排除的误报

- ~~Query 向量 L2 归一化~~：Dify 的 CacheEmbedding 在 embed_query 和 embed_documents 中都做了 L2 归一化，与项目一致。
- ~~BM25 vs jieba TF-IDF~~：Dify 的 Milvus 向量库同样使用内置 BM25 做全文检索。
- ~~min-max 归一化破坏分数~~：所有库都是 rerank 模式，走 `mergeForRerank` 路径，不执行 min-max 归一化。
- ~~input_type 兼容性~~：Dify 也使用 `input_type=query/document`，与项目一致。

---

## 验证：Dify 多知识库混合检索模式（一个 semantic + 其余 hybrid）

### Dify 的完整执行流程

Dify 的 `multiple_retrieve` 在多知识库场景下，每个知识库**独立使用各自的 `retrieval_model["search_method"]`**：

```
multiple_retrieve
  ├── 对每个 dataset 启动线程 → _retriever(dataset_id, query, top_k, ...)
  │     ├── dataset_A (semantic_search)
  │     │     └── RetrievalService.retrieve(retrieval_method="semantic_search")
  │     │           ├── embedding_search → vector.search_by_vector(query, top_k, score_threshold)
  │     │           │     └── 如果有 reranking_model 且 method==semantic → 库内 rerank
  │     │           └── 不触发 full_text_index_search
  │     │
  │     ├── dataset_B (hybrid_search)
  │     │     └── RetrievalService.retrieve(retrieval_method="hybrid_search")
  │     │           ├── embedding_search → vector.search_by_vector(query, top_k, score_threshold)
  │     │           │     └── method==hybrid → 不做库内 rerank，直接 all_documents.extend
  │     │           └── full_text_index_search → vector.search_by_full_text(query, top_k)
  │     │           │     └── method==hybrid → 不做库内 rerank，直接 all_documents.extend
  │     │           └── 两路收集完毕后 → DataPostProcessor(rerank).invoke() 库内融合 rerank
  │     │
  │     └── ...更多库
  │
  └── 所有线程完成后
        └── reranking_enable=True → DataPostProcessor(reranking_mode, reranking_model, weights)
              └── 全局 rerank（score_threshold + top_n 截断）
```

### 关键细节

1. **每个知识库用各自的 search_method**：`_retriever` 里读 `dataset.retrieval_model["search_method"]`，semantic 库只做 embedding_search，hybrid 库做 embedding + full_text。

2. **单路检索（semantic/full_text）也会库内 rerank**：
   - `embedding_search` 中，如果 `retrieval_method == "semantic_search"` 且有 reranking_model，会**在库内先做一次 rerank**，rerank 的 top_n=len(documents)（不截断），只做重排序和 score_threshold 过滤。
   - `full_text_index_search` 同理，`retrieval_method == "full_text_search"` 时也会库内 rerank。

3. **hybrid 库内也有独立 rerank**：
   - `RetrievalService.retrieve()` 中，当 `retrieval_method == "hybrid_search"` 时，两路结果收集后交给 `DataPostProcessor` 做库内融合 rerank（同样用 score_threshold + top_n 截断）。

4. **全局 rerank 是最终兜底**：
   - 所有库的结果合并到 `all_documents` 后，`multiple_retrieve` 再做一次全局 `DataPostProcessor.invoke()`。
   - 全局 rerank 会对所有库的输出统一重排序，然后用全局的 score_threshold + top_k 做最终截断。

5. **score_threshold 的两层应用**：
   - 库内 rerank：`score_threshold` 来自知识库自身的配置
   - 全局 rerank：`score_threshold` 来自 `multiple_retrieval_config.score_threshold`

### 你的项目 vs Dify 的关键差异

| 环节 | Dify | 你的项目 |
|------|------|----------|
| semantic 库是否有 KB 内 rerank | **有**，且 score_threshold 在 rerank 后过滤 | 有（如果 rerankPolicy 不为 null），**但 score_threshold 在 rerank 前就过滤** |
| hybrid 库内 rerank 前是否做阈值过滤 | **不做**，两路结果全部收集后直接交给 rerank | **做**，filterDenseCandidates/trimSparseCandidates 会提前丢候选 |
| 全局 rerank 后是否做阈值过滤 | **做**，最终 score_threshold + top_k 截断 | **做**，GlobalRankingDomainService.postProcess |
| 多库混合（semantic + hybrid）时全局处理 | 所有库结果合并后统一全局 rerank | 所有库结果合并后统一全局 rerank（逻辑一致） |

### 结论

多库混合模式下，Dify 的全局处理逻辑和你的项目基本一致。**真正的差异在于库内层级**：Dify 在 KB 内 hybrid rerank 之前不做阈值过滤，而你的项目做了。这个差异在多库场景下同样存在且同样有害。
