# Agentic RAG 前端成功、失败与阶段事件处理规则

本文档适用于前端通过 `rag-core-int` 的 `POST /api/v1/rag/answer` 接口接收 SSE，并将流程聚合成以下 8 个阶段：

1. 问题理解
2. 初步检索
3. 研究规划
4. 主张研究
5. 证据评估
6. 研究决策
7. 证据定稿
8. 答案生成

## 1. 首先判断整个对话是否成功或失败

### 1.1 Core 对外 SSE 结构

`rag-core-int` 对外发送的 SSE 事件名包括：

| 顶层 SSE `event` | 含义 |
|---|---|
| `rag_progress` | 流程进度 |
| `delta` | 答案文本增量 |
| `reference` | 引用列表 |
| `done` | 整个对话成功完成 |
| `error` | 整个对话失败 |

这里的“顶层 event”指 SSE 协议中的 `event:` 字段。不同的前端 SSE 封装库可能将它命名为 `event`、`type` 或顶层 `event_type`，判断逻辑相同。

例如：

```text
event: rag_progress
data: {
  "stage": "claim_research",
  "status": "milestone",
  "details": {
    "event_type": "llm.attempt.failed"
  }
}
```

### 1.2 整个对话的唯一终态规则

| 收到的内容 | 整体状态 | 前端处理 |
|---|---|---|
| 顶层 `event=done` | 成功 | 停止等待，将整体状态设为成功 |
| 顶层 `event=error` | 失败 | 停止等待，展示 Core 返回的 `code/message` |
| 顶层 `event=rag_progress` 且 `status=failed/error` | 不能判断整体失败 | 只记录当前阶段异常，继续等待 `done/error` |
| `details.event_type=xxx.failed/xxx.error` | 不能判断整体失败 | 只更新对应阶段状态，继续等待 `done/error` |
| 浏览器触发 `EventSource.onerror` | 状态未知 | 表示断线或重连，不能直接显示业务失败 |
| SSE 关闭但没有收到 `done/error` | 状态未知 | 重连或查询 Run 状态，不能直接显示失败 |

结论：

```text
整个对话成功 = 收到 rag-core-int 顶层 SSE event=done
整个对话失败 = 收到 rag-core-int 顶层 SSE event=error
```

顶层 `status` 和 `details.event_type` 都不能单独决定整个对话的终态。

### 1.3 推荐的整体状态代码

```ts
function handleCoreSse(eventName: string, data: any) {
  if (eventName === "done") {
    conversationStatus = "completed";
    return;
  }

  if (eventName === "error") {
    conversationStatus = "failed";
    showError(data.code, data.message);
    markFailureStageRed();
    return;
  }

  if (eventName === "rag_progress") {
    updateStage(data);
  }
}
```

如果前端框架将 SSE 事件名保存为顶层 `event_type`，则把上述 `eventName` 替换为顶层 `event_type` 即可。

## 2. 顶层 status 怎么处理

`rag_progress` 数据中的顶层 `status` 是阶段状态，不是整个对话状态。

| 顶层 `status` | 阶段处理 | 是否影响整个对话 |
|---|---|---|
| `started/running` | 对应阶段显示蓝色进行中 | 否 |
| `completed` | 对应阶段可以显示绿色完成 | 否 |
| `fallback` | 对应阶段显示黄色降级 | 否 |
| `failed/error` | 对应阶段先显示黄色异常或失败候选 | 否，必须继续等待顶层 `done/error` |
| `milestone` | 根据 `details.event_type` 更新阶段 | 否 |

当前 Agentic 事件经过 Core 包装后，顶层 `status` 通常是 `milestone`，所以 Agentic 阶段判断应主要使用 `details.event_type`。

## 3. details.event_type 怎么处理

### 3.1 有效内部事件类型

对于 `rag_progress`，真实的 Agentic 事件类型通常在：

```text
data.details.event_type
```

如果前端数据中同时存在顶层 `event_type` 和 `details.event_type`，只取一个，不能重复处理：

```ts
function effectiveInternalEventType(progress: any): string | undefined {
  if (progress.details?.event_type) {
    return progress.details.event_type;
  }

  // rag_progress/delta/reference/done/error 属于 Core 外层事件名，
  // 不能当成 Agentic 内部事件类型。
  if (
    progress.event_type &&
    !["rag_progress", "delta", "reference", "done", "error"].includes(progress.event_type)
  ) {
    return progress.event_type;
  }

  return undefined;
}
```

### 3.2 failed/error 事件处理表

| `details.event_type` | 所属阶段 | 主界面是否单独展示 | 阶段颜色 | 说明 |
|---|---|---|---|---|
| `llm.attempt.failed` | 按 `stage` 归类 | 不单独展示，只累计警告次数 | 黄色 | 单次模型请求失败，后续可能重试 |
| `llm.fallback.started` | 按 `stage` 归类 | 可显示“正在切换备用模型” | 黄色 | 流程仍在继续 |
| `llm.fallback.completed` | 按 `stage` 归类 | 可显示“备用模型已恢复” | 黄色，或绿色加黄色角标 | 已恢复，不是最终失败 |
| `llm.fallback.failed` | 按 `stage` 归类 | 不单独生成节点 | 黄色 | 等待上层节点或 Core 最终终态 |
| `llm.failed` | 问题理解、研究规划、主张研究、证据评估或答案生成 | 不单独生成节点，错误原因放详情 | 先黄色 | 有些阶段会使用兜底逻辑，不能立即标红 |
| `tool.failed` | 主张研究 | 不单独生成节点，错误原因放详情 | 黄色 | 其他工具、其他主张或下一轮仍可能成功 |
| `claim.failed` | 主张研究 | 汇总成“部分主张失败” | 黄色 | 整个 Run 仍可能继续或生成部分答案 |
| `retrieval.failed` | 初步检索或主张研究 | 不单独生成节点 | 先黄色 | 初步检索通常不可恢复，但仍应等待 Core 顶层 `error` 确认 |
| `node.failed` | 按 `stage` 归类 | 不单独生成节点 | 先黄色，记录为失败候选 | 收到 Core 顶层 `error` 后，才把最终失败阶段标红 |
| `run.failed` | 整体运行 | Core 正常会转换成顶层 SSE `error` | 红色 | 如前端意外收到该进度事件，可作为失败候选，但仍需与顶层 `error` 去重 |
| 任意 `xxx.error` | 按 `stage` 归类 | 与对应 `xxx.failed` 相同 | 默认先黄色 | 当前协议主要使用 `.failed`；未知 `.error` 不应直接判定整体失败 |

### 3.3 哪些内部事件需要过滤

以下事件不应在主界面生成新的一级节点，只用于更新 8 个阶段或放入详情：

```text
llm.started
llm.completed
llm.attempt.started
llm.attempt.completed
tool.started
tool.completed
tool.skipped
node.started
node.completed
answer.delta
answer.references
```

以下事件需要保留，但在主界面只汇总为黄色警告：

```text
llm.attempt.failed
llm.fallback.started
llm.fallback.completed
llm.fallback.failed
llm.failed
tool.failed
claim.failed
retrieval.failed
node.failed
```

## 4. 8 个阶段如何展示

| 前台阶段 | 对应 `stage` | 绿色完成依据 | 黄色依据 | 红色依据 |
|---|---|---|---|---|
| 问题理解 | `question_understanding` | 收到 `question.classified`，且没有降级 | `llm.failed` 后使用兜底；`fallback_used=true` | Core 顶层 `error`，且最终失败阶段是问题理解 |
| 初步检索 | `preliminary_retrieval` | `retrieval.completed` | 检索发生过可恢复降级 | `retrieval.failed` 后收到 Core 顶层 `error` |
| 研究规划 | `research_planning` | `research.plan.completed` 且未降级 | LLM 失败后生成默认主张；`fallback_used=true` | Core 顶层 `error`，且最终失败阶段是研究规划 |
| 主张研究 | `claim_research` | 最终一轮 `research.dispatch.completed` 且主张验证完成 | 重试、模型切换、工具失败、部分主张失败、进入下一轮 | Core 顶层 `error`，且最终失败阶段是主张研究 |
| 证据评估 | `evidence_evaluation` | `evidence.sufficiency.completed` 且证据充分、Grounding 通过 | 证据不足、Grounding 未通过、存在冲突或准备下一轮 | Core 顶层 `error`，且最终失败阶段是证据评估 |
| 研究决策 | `research_decision` | `research.decision.completed` 且决策为 `ANSWER` | `CONTINUE`、`RECONCILE`、`ANSWER_PARTIAL`、`ABSTAIN` | Core 顶层 `error`，且最终失败阶段是研究决策 |
| 证据定稿 | `evidence_finalization` | `evidence.finalized` | 证据为空、较少或最终为部分回答 | Core 顶层 `error`，且最终失败阶段是证据定稿 |
| 答案生成 | `answer_generation` | `answer.completed`，最终收到 Core 顶层 `done` | `answer.skipped` 或部分回答，但最终收到 `done` | 答案生成异常，最终收到 Core 顶层 `error` |

## 5. 多轮阶段不能“一次失败永久标红”

主张研究、证据评估和研究决策可能执行多轮。

| 当前事件 | 聚合阶段状态 |
|---|---|
| `research.dispatch.started` | 蓝色，显示“第 N 轮进行中” |
| 本轮出现 `llm.attempt.failed/tool.failed/claim.failed` | 黄色，显示本轮发生异常但仍在继续 |
| `research.dispatch.completed` 且全部验证成功 | 绿色；如发生过重试可保留黄色角标 |
| `research.dispatch.completed` 且部分主张未验证 | 黄色 |
| `evidence.sufficiency.completed` 且证据不足 | 黄色，等待研究决策 |
| `research.decision.completed: CONTINUE/RECONCILE` | 黄色，显示“准备进入下一轮” |
| 下一轮开始 | 切换为蓝色运行中，保留历史警告次数 |
| 最终收到 Core 顶层 `done` | 整体成功；内部异常最多保留黄色，不得标红 |
| 最终收到 Core 顶层 `error` | 整体失败；将最后一个失败候选阶段标红 |

## 6. 推荐的前端归并逻辑

```ts
function updateStage(progress: any) {
  const stage = progress.stage;
  const type = effectiveInternalEventType(progress);

  if (!type) {
    if (progress.status === "failed" || progress.status === "error") {
      markStageWarning(stage, progress);
      rememberFailureCandidate(stage);
    }
    return;
  }

  if (
    type === "llm.attempt.failed" ||
    type === "llm.fallback.started" ||
    type === "llm.fallback.completed" ||
    type === "llm.fallback.failed" ||
    type === "llm.failed" ||
    type === "tool.failed" ||
    type === "claim.failed" ||
    type === "retrieval.failed" ||
    type === "node.failed" ||
    type.endsWith(".error")
  ) {
    markStageWarning(stage, progress);
    rememberFailureCandidate(stage);
    return;
  }

  updateNormalStageProgress(stage, type, progress);
}
```

收到顶层 `done` 后：

- 整体状态设为成功；
- 已完成且没有警告的阶段显示绿色；
- 发生过重试、降级或部分失败的阶段保留黄色；
- 不允许任何阶段继续显示红色。

收到顶层 `error` 后：

- 整体状态设为失败；
- 展示 Core 返回的 `code/message`；
- 将最后一个失败候选阶段或当前运行阶段标红；
- 其他阶段保持原有绿色或黄色状态；
- 不要再把同一条 `details.event_type=xxx.failed` 重复弹窗。
