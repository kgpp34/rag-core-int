# Agentic RAG 前端 SSE 事件协议

本文档描述前端调用 `POST /api/v1/rag/answer`，并设置
`Accept: text/event-stream`、`planType: AGENTIC_RAG` 时，从
`rag-core-int` 实际收到的 SSE 事件。

本文档以当前 `rag-core-int` 和 `rag-agentic-int` 源码为准。Agentic 图后续增加节点时，
`rag_progress.details.event_type` 可能增加新值；前端必须忽略未知事件，不能因为出现未知值而中断回答。

## 1. 请求示例

```http
POST /api/v1/rag/answer
Accept: text/event-stream
Content-Type: application/json
```

```json
{
  "query": "什么是三重一大项目？",
  "userId": "user-001",
  "docIds": ["doc-1"],
  "planType": "AGENTIC_RAG",
  "memory": {
    "conversationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  }
}
```

注意：core 对外请求字段使用 camelCase，即 `planType`、`userId`、`docIds`，不是 snake_case。

## 2. core 对外事件总览

前端只需要识别以下五个 SSE 事件名：

| SSE 事件名 | 含义 | 是否可能重复 |
|---|---|---|
| `rag_progress` | Agentic 执行进度 | 是 |
| `delta` | 答案文本增量，只包含答案正文 | 是，通常数量很多 |
| `reference` | 参考资料列表（JSON），在 `done` 之前下发 | 否 |
| `done` | Run 成功完成 | 否 |
| `error` | Run 失败、取消或 core 转发失败 | 否 |

agentic-int 的内部事件不会以原 SSE 事件名直接暴露。除特殊事件外，它们会统一转换为
`rag_progress`，内部事件类型保存在 `data.details.event_type`。

映射规则如下：

| agentic-int 事件 | core 对外事件 |
|---|---|
| `answer.delta` | `delta` |
| `answer.references` | 被 core 捕获，用于生成 `reference`（不单独下发） |
| `run.completed` | `reference` + `done` |
| `run.failed` | `error` |
| `run.cancelled` | `error` |
| 其他所有事件 | `rag_progress` |

一次实际运行样本包含 1060 个 agentic-int 事件，经过 core 后为：

| core 事件 | 数量 |
|---|---:|
| `rag_progress` | 97 |
| `delta` | 962 |
| `reference` | 1 |
| `done` | 1 |

962 个 `delta` 是模型逐 token/短文本流式输出，不代表 962 个执行步骤。

## 3. `rag_progress`

### 3.1 数据结构

```json
{
  "eventId": "agentic-19",
  "sequence": 19,
  "traceId": "trace-001",
  "occurredAt": "2026-08-20T06:52:35.550Z",
  "stage": "research_planning",
  "status": "milestone",
  "title": "将问题拆分为可独立检索和验证的主张",
  "elapsedMs": null,
  "details": {
    "seq": 19,
    "event_type": "research.plan.completed",
    "node_id": "plan_claims",
    "created_at": "2026-08-20T06:52:35.544133+00:00",
    "stage": "research_planning",
    "stage_name": "研究规划",
    "action": "将问题拆分为可独立检索和验证的主张"
  }
}
```

字段说明：

| 字段 | 含义 |
|---|---|
| `eventId` | `agentic-` 加 agentic-int 的事件序号 |
| `sequence` | core 生成的进度事件序号；不包含 `delta` |
| `traceId` | core 请求 Trace ID |
| `occurredAt` | core 转换事件的时间 |
| `stage` | 当前业务阶段 |
| `status` | 当前实现中 Agentic 进度通常为 `milestone` |
| `title` | 可直接作为默认进度文案，来源于内部 `action` |
| `elapsedMs` | 内部事件包含 `duration_ms` 时才有值 |
| `details` | agentic-int 内部事件数据 |

前端区分进度类型时必须使用：

```text
data.details.event_type
```

不要使用外层 `status` 判断节点开始或结束。当前 Agentic 内部事件通常没有 `status` 字段，
所以转换后的 `status` 通常都是 `milestone`。

### 3.2 可能的 stage

| stage | 中文含义 | 建议显示文案 |
|---|---|---|
| `run` | Run 初始化或完成 | 正在初始化任务 |
| `question_understanding` | 问题理解 | 正在理解问题 |
| `preliminary_retrieval` | 初步检索 | 正在检索初始证据 |
| `research_planning` | 研究规划 | 正在制定研究计划 |
| `claim_research` | 主张研究 | 正在研究问题要点 |
| `evidence_evaluation` | 证据评估 | 正在评估证据 |
| `research_decision` | 研究决策 | 正在判断是否继续研究 |
| `evidence_finalization` | 证据定稿 | 正在整理引用证据 |
| `answer_generation` | 答案生成 | 正在生成答案 |
| `graph_execution` | 未归类图节点 | 正在执行任务 |
| `agentic` | 缺少 stage 的兜底值 | 正在执行任务 |

## 4. 建议前端特别展示的事件

Agentic 请求可能持续数分钟，前端不能只等待最终答案。建议分为两层展示：

- **主阶段时间线**：展示问题理解、检索、研究、证据评估和答案生成等稳定里程碑；
- **实时活动区**：展示当前正在执行的 LLM 和工具调用，并用完成、失败事件更新状态。

实时活动区应复用或更新已有记录，不要为每个事件永久新增一条一级时间线节点。

### 4.1 `run.started`

Run 已创建并开始执行。

关键字段：

- `run_id`
- `graph_version_id`
- `message_count`
- `doc_id_count`
- `prompt_profile`

建议显示：`正在初始化 Agentic RAG`。

### 4.2 `question.rewrite.completed`

结合历史消息将问题整理为可独立理解的问题。

关键字段：

- `original_question`
- `standalone_question`
- `changed`
- `keywords`
- `scope_constraints`
- `fallback_used`

示例：

```json
{
  "event_type": "question.rewrite.completed",
  "stage_name": "问题理解",
  "standalone_question": "什么是三重一大项目？",
  "changed": false,
  "keywords": ["三重一大", "项目", "定义"]
}
```

### 4.3 `question.classified`

识别问题类型。

关键字段：

- `question`
- `question_type`
- `reasoning`
- `fallback_used`

常见 `question_type` 由 Agentic 模型和图配置决定，前端应显示原值或提供未知值兜底。

### 4.4 `retrieval.completed`

初步检索完成。

关键字段：

- `query`
- `mode`
- `chunk_count`
- `duration_ms`
- `request_id`
- `debug_trace`
- `chunks`

建议仅显示 `chunk_count` 和 `duration_ms`，不要直接渲染 `chunks[].content_preview`。

```json
{
  "event_type": "retrieval.completed",
  "stage_name": "预检索",
  "query": "什么是三重一大项目？",
  "mode": "HYBRID",
  "chunk_count": 12,
  "duration_ms": 1258
}
```

### 4.5 `research.plan.completed`

研究问题拆分完成。

关键字段：

- `question`
- `question_type`
- `claim_count`
- `claims`
- `seed_evidence_count`
- `fallback_used`

`claims` 元素通常包含：

```json
{
  "claim_id": "c0",
  "priority": 0,
  "verified": false,
  "description": "需要独立检索和验证的问题要点"
}
```

前端可以将 `claims[].description` 展示为“研究计划”。

### 4.6 `research.dispatch.started`

开始并行研究各个问题要点。

关键字段：

- `research_round`
- `claim_count`
- `max_parallel`
- `claims`
- `followup_queries`

### 4.7 `research.dispatch.completed`

一轮主张研究结束。

关键字段：

- `research_round`
- `result_count`
- `verified_count`
- `results`

建议只显示 `verified_count/result_count`。`results[].report_preview` 可能很长，不建议直接发送到 UI。

### 4.8 `evidence.sufficiency.completed`

判断现有证据是否足以回答。

关键字段：

- `is_sufficient`
- `confidence`
- `evidence_count`
- `report_count`
- `missing_information`
- `followup_queries`
- `grounded`
- `reasoning`
- `fallback_used`

```json
{
  "event_type": "evidence.sufficiency.completed",
  "is_sufficient": true,
  "confidence": 0.9,
  "evidence_count": 15,
  "missing_information": []
}
```

### 4.9 `research.decision.completed`

决定继续研究还是生成答案。

关键字段：

- `decision`
- `research_round`
- `evidence_count`
- `claim_confidence`
- `sufficiency_confidence`
- `sufficiency_passed`
- `hard_violations`
- `fusion_score`

当前可能的 `decision`：

| decision | 含义 | 建议显示 |
|---|---|---|
| `ANSWER` | 证据充分 | 证据充分，开始生成答案 |
| `ANSWER_PARTIAL` | 只能部分回答 | 将基于现有证据生成部分回答 |
| `CONTINUE` | 继续研究 | 证据不足，继续检索 |
| `RECONCILE` | 协调冲突 | 正在核对冲突证据 |
| `ABSTAIN` | 放弃回答 | 证据不足，无法可靠回答 |

### 4.10 `evidence.finalized`

最终证据集合和引用编号已确定。

关键字段：

- `evidence_count`
- `citation_count`
- `citations`

建议只显示 `citation_count`。内部 `citations[].content_preview` 可能很长，正式引用文件以
`reference` 事件（`done.metadata.references` 与之内容一致）为准。

### 4.11 最终答案生成开始

当前没有单独的 `answer.started` 事件。前端可使用以下组合判断：

```text
details.event_type == "llm.started"
&& details.node_id == "compose_answer"
```

建议显示：`正在生成答案`。

### 4.12 `answer.completed`

答案文本生成完成，但 Run 可能仍在执行最终持久化。

关键字段：

- `answer_char_count`
- `citation_marker_count`
- `answer_preview`

建议显示：`答案生成完成`，但必须等到外层 `done` 后才真正结束请求。

### 4.13 `llm.started` / `llm.completed` / `llm.retry` / `llm.failed`

LLM 调用必须展示在实时活动区，让用户知道系统仍在进行分析或生成。

建议状态映射：

| event_type | 建议状态 | 建议文案 |
|---|---|---|
| `llm.started` | `running` | 正在调用模型：`{operation}` |
| `llm.retry` | `retrying` | 模型调用正在重试 |
| `llm.completed` | `completed` | 模型调用完成（`{duration_ms}` ms） |
| `llm.failed` | `failed` | 模型调用失败：`{message}` |

建议使用 `node_id + operation` 关联同一次调用；如果事件中存在更稳定的请求标识，则优先使用请求标识。
`compose_answer` 对应最终答案生成，可提升为主阶段：`正在生成答案`。

不要向普通用户展示 `input_preview`、`response`、`response_preview` 等模型原始内容。

### 4.14 `tool.started` / `tool.completed` / `tool.failed`

工具调用也必须展示在实时活动区，特别是检索、重排或其他耗时工具。

建议状态映射：

| event_type | 建议状态 | 建议文案 |
|---|---|---|
| `tool.started` | `running` | 正在调用工具：`{tool}` |
| `tool.completed` | `completed` | 工具 `{tool}` 执行完成（`{duration_ms}` ms） |
| `tool.failed` | `failed` | 工具 `{tool}` 执行失败：`{message}` |

同一主张可能重复或并行调用相同工具。建议按 `claim_id + tool` 分组，并在 UI 中显示调用次数，
例如：`正在检索资料（第 3 次）`。如果无法可靠关联 started 和 completed，可将完成事件作为最近一个同名运行中工具的状态更新。

### 4.15 长时间无事件时的展示

前端收到 `llm.started` 或 `tool.started` 后，即使暂时没有后续 SSE，也应保持该活动为运行中并显示经过时间：

```text
正在调用模型 · 已等待 42 秒
正在检索资料 · 已等待 1 分 18 秒
```

计时由前端本地完成，不需要服务端每秒发送事件。收到对应的 completed、failed、外层 `done`
或 `error` 后停止计时。

## 5. 所有潜在的内部事件

以下事件集合来自当前 agentic-int 源码。

### 5.1 Run 生命周期

| event_type | core 输出 | 说明 |
|---|---|---|
| `run.started` | `rag_progress` | Run 开始执行 |
| `run.completed` | `done` | Run 成功完成，不再发送为进度 |
| `run.failed` | `error` | Run 执行失败 |
| `run.cancelled` | `error` | Run 被取消 |

### 5.2 图节点生命周期

| event_type | 说明 | 常见字段 |
|---|---|---|
| `node.started` | 图节点开始 | `node_id`, `node_type`, `research_round` |
| `node.completed` | 图节点结束 | `node_id`, `node_type`, `duration_ms`, `updated_keys` |
| `node.failed` | 图节点失败 | `node_id`, `node_type`, `duration_ms`, `error_type`, `message` |

这些事件数量较多，主要用于调试，不建议作为独立 UI 步骤展示。

### 5.3 问题理解

| event_type | 说明 |
|---|---|
| `question.rewrite.completed` | 问题独立化和关键词提取完成 |
| `question.classified` | 问题类型识别完成 |

### 5.4 检索

| event_type | 说明 | 常见字段 |
|---|---|---|
| `retrieval.started` | 检索开始 | `query`, `mode`, `top_k`, `candidate_k` |
| `retrieval.completed` | 检索完成 | `chunk_count`, `chunks`, `duration_ms`, `debug_trace` |
| `retrieval.failed` | 检索失败 | `error_type`, `message`, `duration_ms` |

检索失败可能被图内策略降级处理，并不一定立刻产生外层 `error`。

### 5.5 研究规划和执行

| event_type | 说明 |
|---|---|
| `research.plan.completed` | 主张拆分完成 |
| `research.dispatch.started` | 开始并行研究主张 |
| `research.dispatch.completed` | 一轮主张研究完成 |
| `claim.failed` | 单个主张研究失败，其他主张可能继续执行 |
| `research.decision.completed` | 决定继续、协调、回答或放弃 |

### 5.6 证据处理

| event_type | 说明 | 常见字段 |
|---|---|---|
| `evidence.merged` | 多个主张的证据合并完成 | `seed_evidence_count`, `claim_evidence_count`, `merged_evidence_count` |
| `evidence.cross_check.completed` | 确定性引用检查完成 | `claim_count`, `passed_count`, `checks` |
| `evidence.sufficiency.completed` | 证据充分性评分完成 | `is_sufficient`, `confidence`, `missing_information` |
| `evidence.grounding.completed` | 主张与证据支持关系检查完成 | `claim_count`, `grounded_count`, `verdicts` |
| `evidence.finalized` | 最终证据和引用编号确定 | `evidence_count`, `citation_count`, `citations` |

### 5.7 LLM 调用

| event_type | 说明 | 常见字段 |
|---|---|---|
| `llm.started` | 一次模型调用开始 | `operation`, `temperature`, `message_count`, `input_preview` |
| `llm.retry` | 模型调用重试 | `reason`, `claim_id` |
| `llm.completed` | 模型调用完成 | `duration_ms`, `request_id`, `usage`, `response` 或 `response_preview` |
| `llm.failed` | 模型调用失败 | `duration_ms`, `error_type`, `message` |

Agentic 一次请求会调用模型多次。所有调用都应在实时活动区展示；除最终 `compose_answer` 外，
不建议把每次 LLM 调用都提升为主阶段时间线节点。

### 5.8 工具调用

| event_type | 说明 | 常见字段 |
|---|---|---|
| `tool.started` | 工具调用开始 | `tool`, `claim_id`, `query` 或 `arguments` |
| `tool.completed` | 工具调用完成 | `tool`, `duration_ms`, `chunk_count` 或 `output_preview` |
| `tool.failed` | 工具调用失败 | `tool`, `duration_ms`, `error_type`, `message` |

工具调用可能并行且重复，应该在实时活动区展示运行状态；可以在“研究问题要点”下面分组或折叠，
避免全部堆成一级时间线节点。

### 5.9 答案生成

| event_type | core 输出 | 说明 |
|---|---|---|
| `answer.skipped` | `rag_progress` | 无有效证据，跳过模型生成并使用拒答文案 |
| `answer.delta` | `delta` | 答案文本增量 |
| `answer.completed` | `rag_progress` | 答案文本生成完成 |

## 6. `delta`

```text
event: delta
data: {"content":"三重"}
```

数据结构：

```json
{
  "content": "答案增量文本"
}
```

前端应按接收顺序直接拼接：

```ts
answer += data.content;
```

不要为每个 `delta` 创建一个 UI 时间线节点。

## 7. `reference`

参考资料不再拼进 `delta` 正文，而是在 `done` 之前通过独立的 `reference` 事件下发，载荷是 JSON，
字段一律使用英文。同一次成功运行只会下发一次。

```text
event: reference
data: {"references":[{"fileName":"三重一大决策制度实施办法.pdf","path":"http://files.example.com/example.pdf","documentId":"doc-1","datasetName":"业务规则库"}]}
```

数据结构：

```json
{
  "references": [
    {
      "fileName": "三重一大决策制度实施办法.pdf",
      "path": "http://files.example.com/example.pdf",
      "documentId": "doc-1",
      "datasetName": "业务规则库"
    }
  ]
}
```

| 字段 | 含义 |
|---|---|
| `fileName` | 文件名 |
| `path` | 文件访问路径，可能为 `null` |
| `documentId` | 文档 ID |
| `datasetName` | 数据集（知识库）名称，取不到名称时退回数据集 ID |

前端收到 `reference` 后即可渲染引用列表，不必等 `done`；`done.metadata.references` 内容与之一致，
仅为兼容保留。

### 7.1 引用范围（AGENTIC_RAG）

`AGENTIC_RAG` 路径下，core 会用 agentic-int `answer.references` 事件给出的"正文实际引用到的来源"
过滤 `output.citations`，因此列表只包含答案真正引用到的文档，而不是本次检索命中的全部文档。

- URL（`path`）与 `datasetName` 仍由 core 依据 `difyFilesUrl` 与文档元数据生成，对外字段不变；
- 同一文档多条证据只输出一条（按 `documentId + fileName` 去重），顺序沿用 `output.citations`；
- agentic-int 未下发该事件、或事件 `references_truncated=true`（引用来源超过 20 条被截断）时，
  回退为 `output.citations` 全量，避免混布或截断导致丢引用。

非 `AGENTIC_RAG` 路径不受影响，仍按下发时的检索结果生成。

## 8. `done`

只有 agentic-int 发出 `run.completed` 后，core 才发送 `done`。

```text
event: done
data: {"finishReason":"stop","metadata":{...}}
```

```json
{
  "finishReason": "stop",
  "metadata": {
    "runId": "03b6b642-db31-469d-955b-0a671a08fd3b",
    "status": "completed",
    "partial": false,
    "abstain": false,
    "references": [
      {
        "fileName": "三重一大决策制度实施办法.pdf",
        "path": "http://files.example.com/example.pdf",
        "documentId": "doc-1",
        "datasetName": "业务规则库"
      }
    ],
    "citations": [
      {
        "citation_no": 0,
        "evidence_ref": "ev_xxx",
        "chunk_id": "chunk-1",
        "document_id": "doc-1",
        "knowledge_base_id": "kb-1",
        "content": "引用证据原文",
        "metadata": {}
      }
    ]
  }
}
```

字段说明：

| 字段 | 含义 |
|---|---|
| `runId` | Agentic Run ID，可用于查询 core 持久化状态 |
| `status` | 正常完成时为 `completed` |
| `partial` | 是否仅能部分回答 |
| `abstain` | 是否因证据不足拒绝回答 |
| `references` | core 标准化后的文件级引用，推荐前端展示；每项包含 `fileName`、`path`、`documentId`、`datasetName` |
| `citations` | Agentic 原始片段级引用，用于精细引用展示 |

收到 `done` 后，前端应停止 loading、关闭 SSE 流并展示引用。

## 9. `error`

```text
event: error
data: {"code":"AGENTIC_RUN_FAILED","message":"Agentic Run 执行失败"}
```

```json
{
  "code": "AGENTIC_RUN_FAILED",
  "message": "Agentic Run 执行失败"
}
```

可能的错误码：

| code | 含义 |
|---|---|
| `PLAN_UNSUPPORTED` | Agentic RAG 未启用 |
| `AGENTIC_UNAVAILABLE` | agentic-int 无法连接 |
| `AGENTIC_TIMEOUT` | Agentic 请求超时 |
| `AGENTIC_RUN_FAILED` | Run 执行失败或取消 |
| `AGENTIC_PROTOCOL_ERROR` | Agentic 返回协议不符合预期 |
| `INTERNAL_ERROR` | core 未分类内部异常 |

收到 `error` 后，前端应停止 loading 并关闭 SSE 流。已经收到的部分 `delta` 可以保留，但需要标记回答未完整完成。

## 10. 推荐的前端展示和聚合方式

```ts
const visibleProgressEvents = new Set([
  "run.started",
  "question.rewrite.completed",
  "question.classified",
  "retrieval.completed",
  "research.plan.completed",
  "research.dispatch.started",
  "research.dispatch.completed",
  "evidence.sufficiency.completed",
  "research.decision.completed",
  "evidence.finalized",
  "answer.skipped",
  "answer.completed",
  "llm.started",
  "llm.retry",
  "llm.completed",
  "llm.failed",
  "tool.started",
  "tool.completed",
  "tool.failed",
]);

function shouldDisplayProgress(data: RagProgressEvent): boolean {
  const eventType = String(data.details?.event_type ?? "");
  return visibleProgressEvents.has(eventType);
}
```

建议把 `node.*` 放到开发调试日志；`llm.*` 和 `tool.*` 必须展示，但放在实时活动区，
不要全部占用主阶段时间线。

推荐的页面结构：

```text
主阶段
  ✓ 已理解问题
  ✓ 已制定研究计划
  ● 正在研究 3 个问题要点

实时活动
  ● 正在调用模型：研究主张 · 18 秒
  ✓ 检索工具执行完成 · 1.2 秒
  ● 正在调用检索工具 · 第 3 次

答案
  正在流式输出……
```

为了控制事件量，前端可以：

- 用 started 创建活动，completed/failed 更新同一活动；
- 将相同 `claim_id + tool` 或 `node_id + operation` 的重复调用聚合；
- 实时活动区只保留最近若干条，历史活动折叠展示；
- 对 `answer.delta` 只拼接文本，不创建活动记录；
- 不丢弃失败事件，失败后图可能降级继续执行。

## 11. TypeScript 类型参考

```ts
type AgenticSseEventName = "rag_progress" | "delta" | "reference" | "done" | "error";

interface RagProgressEvent {
  eventId: string;
  sequence: number;
  traceId: string | null;
  occurredAt: string;
  stage: string;
  status: string;
  title: string;
  elapsedMs: number | null;
  details: Record<string, unknown> & {
    seq?: number;
    event_type?: string;
    node_id?: string | null;
    stage?: string;
    stage_name?: string;
    action?: string;
  };
}

interface RagAnswerStreamDelta {
  content: string;
}

interface RagReferenceEvent {
  references: Reference[];
}

interface Reference {
  fileName: string;
  path: string | null;
  documentId: string | null;
  datasetName: string | null;
}

interface AgenticCitation {
  citation_no: number;
  evidence_ref: string;
  chunk_id: string;
  document_id: string;
  knowledge_base_id: string | null;
  content: string;
  metadata: Record<string, unknown>;
}

interface RagAnswerStreamDone {
  finishReason: string | null;
  metadata: {
    runId: string;
    status: string;
    partial: boolean;
    abstain: boolean;
    references: Reference[];
    citations: AgenticCitation[];
  };
}

interface RagAnswerStreamError {
  code: string;
  message: string;
}
```

## 12. 数据量和安全注意事项

当前 core 会把 agentic-int 的内部事件数据整体放入 `rag_progress.details`。其中可能包含：

- 用户问题和改写问题；
- LLM 输入、输出预览和 token 使用量；
- 检索 query；
- 检索片段及 `content_preview`；
- 主张研究报告预览；
- 证据和引用内容；
- 内部调试字段。

前端不得直接把整个 `details` 渲染到页面，也不应将其原样写入浏览器长期存储。
正式上线前建议由 core 对进度事件做字段白名单和体积限制，只保留 UI 确实需要的字段。

## 13. 完整成功请求示例

本节给出一次 Agentic RAG 请求从开始到完成时，前端实际需要处理的完整事件链路。为了让示例可读，
删除了 `input_preview`、`response_preview`、`chunks` 等超长或敏感字段，并将实际可能出现的数百个
`answer.delta` 缩减为 4 个；事件名称、外层结构、前端依赖字段和先后关系保持不变。

图中的 `node.*` 调试事件未放入这个 UI 联调示例，因为推荐前端不展示它们。它们出现时仍然是
`rag_progress`，前端忽略即可。

### 13.1 请求

```http
POST /api/v1/rag/answer HTTP/1.1
Accept: text/event-stream
Content-Type: application/json
```

```json
{
  "query": "什么是三重一大项目？",
  "userId": "user-001",
  "docIds": ["doc-1"],
  "planType": "AGENTIC_RAG",
  "memory": {
    "conversationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  }
}
```

### 13.2 完整 SSE 响应

#### 1. Run 开始

```text
event: rag_progress
data: {"eventId":"agentic-1","sequence":1,"traceId":"trace-001","occurredAt":"2026-08-20T06:51:12.945Z","stage":"run","status":"milestone","title":"加载固定 high 图并开始执行","elapsedMs":null,"details":{"seq":1,"event_type":"run.started","stage":"run","stage_name":"运行初始化","action":"加载固定 high 图并开始执行","run_id":"03b6b642-db31-469d-955b-0a671a08fd3b","graph_version_id":"93870562-07e4-458f-aaa6-6027b1eb6c3a","message_count":3,"doc_id_count":1}}
```

前端主阶段显示：`正在初始化 Agentic RAG`。

#### 2. 调用 LLM 理解问题

```text
event: rag_progress
data: {"eventId":"agentic-3","sequence":2,"traceId":"trace-001","occurredAt":"2026-08-20T06:51:13.048Z","stage":"question_understanding","status":"milestone","title":"结合对话改写为独立检索问题并提取关键词","elapsedMs":null,"details":{"seq":3,"event_type":"llm.started","node_id":"formalize_question","stage":"question_understanding","stage_name":"问题理解","action":"结合对话改写为独立检索问题并提取关键词","operation":"问题独立化与关键词提取","message_count":3,"temperature":0.1}}

event: rag_progress
data: {"eventId":"agentic-4","sequence":3,"traceId":"trace-001","occurredAt":"2026-08-20T06:51:24.726Z","stage":"question_understanding","status":"milestone","title":"结合对话改写为独立检索问题并提取关键词","elapsedMs":11678,"details":{"seq":4,"event_type":"llm.completed","node_id":"formalize_question","stage":"question_understanding","stage_name":"问题理解","action":"结合对话改写为独立检索问题并提取关键词","operation":"问题独立化与关键词提取","request_id":"09be81e127054bd99459bf434d18ac96","duration_ms":11678,"usage":{"prompt_tokens":324,"completion_tokens":2083,"total_tokens":2407}}}

event: rag_progress
data: {"eventId":"agentic-5","sequence":4,"traceId":"trace-001","occurredAt":"2026-08-20T06:51:24.735Z","stage":"question_understanding","status":"milestone","title":"结合对话改写为独立检索问题并提取关键词","elapsedMs":null,"details":{"seq":5,"event_type":"question.rewrite.completed","node_id":"formalize_question","stage":"question_understanding","stage_name":"问题理解","action":"结合对话改写为独立检索问题并提取关键词","original_question":"什么是三重一大项目？","standalone_question":"什么是三重一大项目？","changed":false,"keywords":["三重一大","项目","定义"],"fallback_used":false}}
```

实时活动区先显示“正在调用模型：问题独立化与关键词提取”，随后将同一条活动更新为“模型调用完成 · 11.7 秒”。
主阶段更新为“已理解问题”。

#### 3. 初步检索

```text
event: rag_progress
data: {"eventId":"agentic-13","sequence":5,"traceId":"trace-001","occurredAt":"2026-08-20T06:51:30.380Z","stage":"preliminary_retrieval","status":"milestone","title":"使用混合检索获取规划所需的初始证据","elapsedMs":null,"details":{"seq":13,"event_type":"retrieval.started","node_id":"pre_search","stage":"preliminary_retrieval","stage_name":"预检索","action":"使用混合检索获取规划所需的初始证据","tool":"hybrid_search","query":"什么是三重一大项目？","mode":"HYBRID","top_k":12}}

event: rag_progress
data: {"eventId":"agentic-14","sequence":6,"traceId":"trace-001","occurredAt":"2026-08-20T06:51:31.639Z","stage":"preliminary_retrieval","status":"milestone","title":"使用混合检索获取规划所需的初始证据","elapsedMs":1258,"details":{"seq":14,"event_type":"retrieval.completed","node_id":"pre_search","stage":"preliminary_retrieval","stage_name":"预检索","action":"使用混合检索获取规划所需的初始证据","tool":"hybrid_search","query":"什么是三重一大项目？","mode":"HYBRID","chunk_count":12,"duration_ms":1258,"request_id":"0b8664bb-a8e8-46c4-bd27-5ba389664f07"}}
```

前端显示：`初步检索完成，找到 12 条证据 · 1.3 秒`。

#### 4. 调用 LLM 制定研究计划，中途发生一次重试

```text
event: rag_progress
data: {"eventId":"agentic-17","sequence":7,"traceId":"trace-001","occurredAt":"2026-08-20T06:51:31.668Z","stage":"research_planning","status":"milestone","title":"将问题拆分为可独立检索和验证的主张","elapsedMs":null,"details":{"seq":17,"event_type":"llm.started","node_id":"plan_claims","stage":"research_planning","stage_name":"研究规划","action":"将问题拆分为可独立检索和验证的主张","operation":"研究主张拆解","temperature":0.2}}

event: rag_progress
data: {"eventId":"agentic-18","sequence":8,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:01.104Z","stage":"research_planning","status":"milestone","title":"将问题拆分为可独立检索和验证的主张","elapsedMs":null,"details":{"seq":18,"event_type":"llm.retry","node_id":"plan_claims","stage":"research_planning","stage_name":"研究规划","action":"将问题拆分为可独立检索和验证的主张","operation":"研究主张拆解","reason":"模型输出未通过结构校验"}}

event: rag_progress
data: {"eventId":"agentic-19","sequence":9,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:35.540Z","stage":"research_planning","status":"milestone","title":"将问题拆分为可独立检索和验证的主张","elapsedMs":63871,"details":{"seq":19,"event_type":"llm.completed","node_id":"plan_claims","stage":"research_planning","stage_name":"研究规划","action":"将问题拆分为可独立检索和验证的主张","operation":"研究主张拆解","duration_ms":63871,"request_id":"460ee4f3e686463eafc0449b0fa7bf90"}}

event: rag_progress
data: {"eventId":"agentic-20","sequence":10,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:35.549Z","stage":"research_planning","status":"milestone","title":"将问题拆分为可独立检索和验证的主张","elapsedMs":null,"details":{"seq":20,"event_type":"research.plan.completed","node_id":"plan_claims","stage":"research_planning","stage_name":"研究规划","action":"将问题拆分为可独立检索和验证的主张","claim_count":3,"claims":[{"claim_id":"c0","priority":0,"verified":false,"description":"三重一大的定义是什么？"},{"claim_id":"c1","priority":1,"verified":false,"description":"重大项目安排包括哪些事项？"},{"claim_id":"c2","priority":2,"verified":false,"description":"其他三类事项如何界定？"}],"seed_evidence_count":12,"fallback_used":false}}
```

实时活动区依次显示“正在调用模型”“模型调用正在重试”“模型调用完成 · 1 分 4 秒”。
主阶段展示 3 个研究要点。

#### 5. 并行研究并调用检索工具

```text
event: rag_progress
data: {"eventId":"agentic-22","sequence":11,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:35.575Z","stage":"claim_research","status":"milestone","title":"并行派发尚未验证的研究主张","elapsedMs":null,"details":{"seq":22,"event_type":"research.dispatch.started","node_id":"dispatch_claim_research","stage":"claim_research","stage_name":"主张研究","action":"并行派发尚未验证的研究主张","research_round":0,"claim_count":3,"max_parallel":2}}

event: rag_progress
data: {"eventId":"agentic-30","sequence":12,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:35.620Z","stage":"claim_research","status":"milestone","title":"由研究模型选择检索工具或提交主张报告","elapsedMs":null,"details":{"seq":30,"event_type":"llm.started","node_id":"call_research_model","stage":"claim_research","stage_name":"主张研究","action":"由研究模型选择检索工具或提交主张报告","claim_id":"c0","operation":"单主张工具选择与研究报告生成","tool_round":0}}

event: rag_progress
data: {"eventId":"agentic-31","sequence":13,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:40.137Z","stage":"claim_research","status":"milestone","title":"由研究模型选择检索工具或提交主张报告","elapsedMs":4508,"details":{"seq":31,"event_type":"llm.completed","node_id":"call_research_model","stage":"claim_research","stage_name":"主张研究","action":"由研究模型选择检索工具或提交主张报告","claim_id":"c0","operation":"单主张工具选择与研究报告生成","duration_ms":4508,"request_id":"d7d5abd045b24e5398a5bc381c024f82","tool_calls":[{"name":"hybrid_search","arguments":{"query":"三重一大 全称 定义 核心内容","top_k":5}}]}}

event: rag_progress
data: {"eventId":"agentic-33","sequence":14,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:40.155Z","stage":"claim_research","status":"milestone","title":"执行模型选择的内部知识库检索工具","elapsedMs":null,"details":{"seq":33,"event_type":"tool.started","node_id":"execute_retrieval","stage":"claim_research","stage_name":"主张研究","action":"执行模型选择的内部知识库检索工具","claim_id":"c0","tool":"hybrid_search","query":"三重一大 全称 定义 核心内容","mode":"HYBRID","top_k":5}}

event: rag_progress
data: {"eventId":"agentic-37","sequence":15,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:41.323Z","stage":"claim_research","status":"milestone","title":"执行模型选择的内部知识库检索工具","elapsedMs":1167,"details":{"seq":37,"event_type":"tool.completed","node_id":"execute_retrieval","stage":"claim_research","stage_name":"主张研究","action":"执行模型选择的内部知识库检索工具","claim_id":"c0","tool":"hybrid_search","mode":"HYBRID","chunk_count":5,"new_evidence_count":5,"duration_ms":1167,"request_id":"e1aa7f2e-6914-4a2e-9ae6-17bff2ae339d"}}

event: rag_progress
data: {"eventId":"agentic-40","sequence":16,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:41.349Z","stage":"claim_research","status":"milestone","title":"由研究模型选择检索工具或提交主张报告","elapsedMs":null,"details":{"seq":40,"event_type":"llm.started","node_id":"call_research_model","stage":"claim_research","stage_name":"主张研究","action":"由研究模型选择检索工具或提交主张报告","claim_id":"c0","operation":"单主张工具选择与研究报告生成","tool_round":1}}

event: rag_progress
data: {"eventId":"agentic-45","sequence":17,"traceId":"trace-001","occurredAt":"2026-08-20T06:52:56.120Z","stage":"claim_research","status":"milestone","title":"由研究模型选择检索工具或提交主张报告","elapsedMs":14770,"details":{"seq":45,"event_type":"llm.completed","node_id":"call_research_model","stage":"claim_research","stage_name":"主张研究","action":"由研究模型选择检索工具或提交主张报告","claim_id":"c0","operation":"单主张工具选择与研究报告生成","duration_ms":14770,"request_id":"57db221f1647433cb5c829d244377ee9"}}

event: rag_progress
data: {"eventId":"agentic-90","sequence":18,"traceId":"trace-001","occurredAt":"2026-08-20T06:54:20.500Z","stage":"claim_research","status":"milestone","title":"汇总本轮主张研究结果","elapsedMs":null,"details":{"seq":90,"event_type":"research.dispatch.completed","node_id":"dispatch_claim_research","stage":"claim_research","stage_name":"主张研究","action":"汇总本轮主张研究结果","research_round":0,"result_count":3,"verified_count":3}}
```

实时活动区在这一阶段应类似：

```text
✓ c0 · 模型选择研究动作 · 4.5 秒
✓ c0 · hybrid_search · 找到 5 条证据 · 1.2 秒
✓ c0 · 模型生成研究报告 · 14.8 秒
● c1 · hybrid_search · 已等待 8 秒
```

并行的 `c1`、`c2` 会产生同类事件。前端按 `claim_id` 分组，不要让一个主张的完成事件覆盖另一个主张。

#### 6. 合并、评估并确定最终证据

```text
event: rag_progress
data: {"eventId":"agentic-91","sequence":19,"traceId":"trace-001","occurredAt":"2026-08-20T06:54:20.510Z","stage":"evidence_evaluation","status":"milestone","title":"合并各主张证据并去重","elapsedMs":null,"details":{"seq":91,"event_type":"evidence.merged","node_id":"merge_evidence","stage":"evidence_evaluation","stage_name":"证据评估","action":"合并各主张证据并去重","seed_evidence_count":12,"claim_evidence_count":21,"merged_evidence_count":18}}

event: rag_progress
data: {"eventId":"agentic-100","sequence":20,"traceId":"trace-001","occurredAt":"2026-08-20T06:55:10.100Z","stage":"evidence_evaluation","status":"milestone","title":"判断当前证据是否足以回答问题","elapsedMs":null,"details":{"seq":100,"event_type":"evidence.sufficiency.completed","node_id":"evaluate_sufficiency","stage":"evidence_evaluation","stage_name":"证据评估","action":"判断当前证据是否足以回答问题","is_sufficient":true,"confidence":0.91,"evidence_count":18,"missing_information":[],"grounded":true,"fallback_used":false}}

event: rag_progress
data: {"eventId":"agentic-101","sequence":21,"traceId":"trace-001","occurredAt":"2026-08-20T06:55:10.110Z","stage":"research_decision","status":"milestone","title":"根据证据质量决定下一步","elapsedMs":null,"details":{"seq":101,"event_type":"research.decision.completed","node_id":"decide_next_step","stage":"research_decision","stage_name":"研究决策","action":"根据证据质量决定下一步","decision":"ANSWER","research_round":0,"evidence_count":18,"sufficiency_confidence":0.91,"sufficiency_passed":true}}

event: rag_progress
data: {"eventId":"agentic-110","sequence":22,"traceId":"trace-001","occurredAt":"2026-08-20T06:57:55.190Z","stage":"evidence_finalization","status":"milestone","title":"确定最终证据和引用编号","elapsedMs":null,"details":{"seq":110,"event_type":"evidence.finalized","node_id":"finalize_evidence","stage":"evidence_finalization","stage_name":"证据定稿","action":"确定最终证据和引用编号","evidence_count":15,"citation_count":15}}
```

前端主阶段依次显示：`已合并 18 条证据`、`证据充分度 91%`、`证据充分，开始生成答案`、
`已确定 15 条引用`。

#### 7. 调用 LLM 流式生成答案

```text
event: rag_progress
data: {"eventId":"agentic-111","sequence":23,"traceId":"trace-001","occurredAt":"2026-08-20T06:57:55.200Z","stage":"answer_generation","status":"milestone","title":"基于最终证据流式生成带引用答案","elapsedMs":null,"details":{"seq":111,"event_type":"llm.started","node_id":"compose_answer","stage":"answer_generation","stage_name":"答案生成","action":"基于最终证据流式生成带引用答案","operation":"最终带引用答案生成","message_count":2}}

event: delta
data: {"content":"三重一大是指"}

event: delta
data: {"content":"重大决策、重要人事任免、"}

event: delta
data: {"content":"重大项目安排和大额度资金运作"}

event: delta
data: {"content":"。[ID:1][ID:2]"}

event: rag_progress
data: {"eventId":"agentic-1057","sequence":24,"traceId":"trace-001","occurredAt":"2026-08-20T06:59:38.033Z","stage":"answer_generation","status":"milestone","title":"基于最终证据流式生成带引用答案","elapsedMs":102831,"details":{"seq":1057,"event_type":"llm.completed","node_id":"compose_answer","stage":"answer_generation","stage_name":"答案生成","action":"基于最终证据流式生成带引用答案","operation":"最终带引用答案生成","duration_ms":102831,"response_char_count":1746}}

event: rag_progress
data: {"eventId":"agentic-1058","sequence":25,"traceId":"trace-001","occurredAt":"2026-08-20T06:59:38.041Z","stage":"answer_generation","status":"milestone","title":"基于最终证据流式生成带引用答案","elapsedMs":null,"details":{"seq":1058,"event_type":"answer.completed","node_id":"compose_answer","stage":"answer_generation","stage_name":"答案生成","action":"基于最终证据流式生成带引用答案","answer_char_count":1746,"citation_marker_count":36}}
```

收到 `llm.started` 后，实时活动区显示“正在生成答案”并持续计时；每个 `delta` 只追加到答案区域。
收到 `llm.completed` 后，将活动更新为“答案模型调用完成 · 1 分 43 秒”。此时仍不能结束请求。

#### 8. Run 完成

```text
event: reference
data: {"references":[{"fileName":"中国金融期货交易所“三重一大”决策制度实施办法.pdf","path":"http://files.example.com/example.pdf","documentId":"doc-1","datasetName":"业务规则库"}]}

event: done
data: {"finishReason":"stop","metadata":{"runId":"03b6b642-db31-469d-955b-0a671a08fd3b","status":"completed","partial":false,"abstain":false,"references":[{"fileName":"中国金融期货交易所“三重一大”决策制度实施办法.pdf","path":"http://files.example.com/example.pdf","documentId":"doc-1","datasetName":"业务规则库"}],"citations":[{"citation_no":1,"evidence_ref":"ev_f9f2a5f248ae38a631d06b306c3dfa43bfdadc40e81c9e844691e1e23a643b36","chunk_id":"467331366861420151","document_id":"doc-1","knowledge_base_id":"kb-1","content":"三重一大事项包括重大决策、重要人事任免、重大项目安排和大额度资金运作。","metadata":{"document_name":"中国金融期货交易所“三重一大”决策制度实施办法.pdf"}}]}}
```

收到 `reference` 后即可渲染引用文件列表；收到 `done` 后前端才执行以下动作：

1. 停止所有运行中计时器；
2. 停止 loading；
3. 关闭 SSE；
4. 展示 `metadata.references`（与 `reference` 事件内容一致，兜底用）；
5. 根据 `partial` 和 `abstain` 决定是否显示“部分回答”或“无法可靠回答”提示。

### 13.3 最终页面效果示例

```text
✓ 已理解问题
✓ 初步检索完成，找到 12 条证据
✓ 已制定研究计划，共 3 个研究要点
✓ 已完成 3 个研究要点
✓ 证据充分度 91%
✓ 已确定 15 条引用
✓ 答案生成完成

活动记录（可折叠）
  ✓ 问题独立化与关键词提取 · 11.7 秒
  ↻ 研究主张拆解重试 1 次
  ✓ 研究主张拆解 · 1 分 4 秒
  ✓ c0 · hybrid_search · 找到 5 条证据 · 1.2 秒
  ✓ c0 · 研究报告生成 · 14.8 秒
  ✓ 最终带引用答案生成 · 1 分 43 秒

答案
  三重一大是指重大决策、重要人事任免、重大项目安排和
  大额度资金运作。[ID:1][ID:2]

参考文件
  中国金融期货交易所“三重一大”决策制度实施办法.pdf
```

前端必须以最后的 `done` 或 `error` 作为请求终止信号，不能因为某个 `llm.completed`、
`tool.completed` 或 `answer.completed` 提前停止监听。
