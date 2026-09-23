# RAG SSE 流程事件推送设计方案

## 1. 背景与目标

当前 `POST /api/v1/rag/answer` 的 SSE 接口只向前台推送 LLM 输出：

```text
delta -> delta -> ... -> done
```

在首个 `delta` 到达前，服务端实际上已经完成了查询规划、query rewrite、embedding、多知识库检索、rerank、上下文组装等工作。当前台等待时间较长时，用户只能看到空白状态，无法知道系统正在执行什么。

本方案的目标是为 SSE 接口增加一种新的事件类型：

```text
rag_progress
```

前台可以通过该事件展示 RAG 处理进度，例如：

```text
正在改写查询...
正在检索 3 个知识库...
正在对检索结果进行重排序...
正在生成答案...
已收到首个回答内容
```

同时，事件应携带阶段开始、结束、耗时、结果摘要等信息，便于前台展示轻量的流程详情。

## 2. 设计边界

### 2.1 流程事件不是 Trace

SSE 流程事件面向最终用户和前台产品体验，Trace 面向研发和运维排障。两者不能直接等同。

| 对比项 | SSE 流程事件 | Trace Span |
| --- | --- | --- |
| 使用者 | 前台和最终用户 | 研发、运维 |
| 目标 | 告知用户当前处理进度 | 精确分析单请求链路 |
| 稳定性 | 属于对外 API 协议，必须稳定 | 可以随内部实现调整 |
| 粒度 | 只推送用户可理解的重要阶段 | 可以包含细粒度内部调用 |
| 数据内容 | 脱敏后的阶段状态和结果摘要 | 可包含更多内部属性 |
| 传输 | SSE | OpenTelemetry / OTLP |

因此：

- 不把所有 Trace Span 原样推送给前台。
- 不向前台暴露模型 endpoint、Milvus collection、内部异常堆栈等信息。
- 不因为内部类名或方法名变化而修改 SSE 协议。
- SSE 事件可以携带 `traceId`，方便用户反馈问题后由研发查询完整 Trace。

### 2.2 第一版只推送关键阶段

第一版建议推送以下阶段：

| 阶段代码 | 用户可见名称 | 是否默认推送 |
| --- | --- | --- |
| `query_rewrite` | 正在改写检索问题 | 按需 |
| `retrieval` | 正在检索知识库 | 是 |
| `rerank` | 正在对检索结果重排序 | 按需 |
| `answer_generation` | 正在生成答案 | 是 |

未开启 query rewrite 时，前台收到的第一个业务流程事件应为 `retrieval started`。

以下内部阶段暂不默认推送：

- 查询规划
- embedding
- 单个知识库的 dense / sparse 路由
- 文档元数据补齐
- prompt 组装
- 会话记忆读取

这些阶段对研发排障有价值，但对普通用户不容易理解，且会造成前台事件过于频繁。后续如有调试页面需求，可以增加 `detailLevel` 或专用调试接口。

## 3. 现有实现约束

当前代码中的关键事实：

1. `RagController.answerStream()` 创建 `SseEmitter`，并将 `RagApiService.StreamEvent` 转换为 SSE。
2. `RagApiService.streamAnswer()` 在调用 LLM 前执行 `prepareAnswer()`。
3. `prepareAnswer()` 内部完成查询规划、query rewrite、检索、模型解析、会话记忆和 prompt 组装。
4. 当前只有 `delta`、`done`、`error` 三类 SSE 事件。
5. 多知识库检索使用虚拟线程并行执行。
6. 检索引擎属于独立模块，不应直接依赖 Web 层的 `SseEmitter`。

这意味着流程事件不能只在 `handleStreamChunk()` 中增加，而要让事件发布能力贯穿 answer 编排和检索执行过程。

## 4. SSE 协议设计

### 4.1 保持现有事件兼容

保留现有事件：

| SSE event name | 说明 |
| --- | --- |
| `delta` | LLM 增量输出，只承载答案正文 |
| `reference` | 参考资料列表（JSON），在答案正文之后、`done` 之前下发 |
| `done` | 流式请求正常结束 |
| `error` | 流式请求失败 |

新增事件：

| SSE event name | 说明 |
| --- | --- |
| `rag_progress` | RAG 流程阶段事件 |

旧版前台如果只处理 `delta`、`done`、`error`，应能够忽略未知的 `rag_progress` 事件，不受影响。

参考资料不再拼进 `delta` 正文，改为独立的 `reference` 事件，载荷是 JSON，字段一律使用英文：

```text
event: reference
data:{"references":[{"fileName":"程序化交易管理办法.pdf","path":"https://example.com/files/doc-1","documentId":"doc-1","datasetName":"业务规则库"},{"fileName":"没有链接的文档.pdf","path":null,"documentId":"doc-2","datasetName":"业务规则库"}]}
```

| 字段 | 含义 |
| --- | --- |
| `fileName` | 文件名 |
| `path` | 文件访问路径，可能为 `null` |
| `documentId` | 文档 ID |
| `datasetName` | 数据集（知识库）名称，取不到名称时退回数据集 ID |

成功结束的流一定会下发一次 `reference` 事件；答案为空或与问题无关时 `references` 为空数组。`reference` 与 `done.metadata.references`（Agentic 路径）内容一致，前者用于流式实时渲染，后者保留兼容。

### 4.2 统一流程事件模型

建议新增：

```java
public record RagProgressEvent(
        String eventId,
        long sequence,
        String traceId,
        Instant occurredAt,
        String stage,
        String status,
        String title,
        Long elapsedMs,
        Map<String, Object> details
) {}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `eventId` | String | 当前阶段实例 ID，用于匹配开始和结束事件 |
| `sequence` | long | 请求内单调递增序号，前台按此排序 |
| `traceId` | String | 当前请求 TraceID，便于问题反馈和排障 |
| `occurredAt` | Instant | 事件发生的服务端时间，使用 ISO-8601 序列化 |
| `stage` | String | 稳定的阶段代码 |
| `status` | String | 阶段状态 |
| `title` | String | 服务端提供的默认展示文案 |
| `elapsedMs` | Long | 阶段结束时的耗时，开始事件为空 |
| `details` | Map | 脱敏后的阶段结果摘要 |

### 4.3 状态定义

建议使用以下稳定状态：

| 状态 | 说明 |
| --- | --- |
| `started` | 阶段开始 |
| `completed` | 阶段成功完成 |
| `fallback` | 阶段失败但已降级，主流程继续 |
| `skipped` | 阶段未执行 |
| `failed` | 阶段失败，主流程无法继续 |
| `milestone` | 阶段中的重要里程碑，例如首 token |

开始和结束事件使用同一个 `eventId`：

```text
eventId=query-rewrite-1, status=started
eventId=query-rewrite-1, status=completed, elapsedMs=385
```

### 4.4 为什么使用单一 `rag_progress` 事件名

不建议为每个阶段定义独立 SSE event name，例如：

```text
query_rewrite_started
query_rewrite_completed
retrieval_started
retrieval_completed
```

原因：

- 每增加一个阶段都需要扩展前台事件监听器。
- 阶段状态和阶段类型被编码在 event name 中，不便于统一处理。
- 难以增加 `fallback`、`skipped`、`milestone` 等状态。

使用单一 `rag_progress` 后，前台只需要一个统一处理器：

```javascript
switch (event.stage) {
  case "query_rewrite":
  case "retrieval":
  case "rerank":
  case "answer_generation":
    updateProgress(event);
}
```

## 5. 事件示例

### 5.1 Query rewrite

开始：

```text
event: rag_progress
data: {
  "eventId": "query-rewrite-1",
  "sequence": 3,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:00.100Z",
  "stage": "query_rewrite",
  "status": "started",
  "title": "正在改写检索问题",
  "elapsedMs": null,
  "details": {}
}
```

完成：

```text
event: rag_progress
data: {
  "eventId": "query-rewrite-1",
  "sequence": 4,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:00.485Z",
  "stage": "query_rewrite",
  "status": "completed",
  "title": "检索问题改写完成",
  "elapsedMs": 385,
  "details": {
    "queryCount": 3,
    "subQueries": [
        "子问题1",
        "子问题2",
        "子问题3"
    ]
  }
}
```

Query rewrite 当前存在失败后降级为原始 query 的逻辑。此时不应发送 `failed`，而应发送：

```text
event: rag_progress
data: {
  "eventId": "query-rewrite-1",
  "sequence": 4,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:00.510Z",
  "stage": "query_rewrite",
  "status": "fallback",
  "title": "检索问题改写未完成，已使用原始问题继续检索",
  "elapsedMs": 410,
  "details": {
    "queryCount": 1
  }
}
```

### 5.2 多知识库检索

开始：

```text
event: rag_progress
data: {
  "eventId": "retrieval-1",
  "sequence": 5,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:00.520Z",
  "stage": "retrieval",
  "status": "started",
  "title": "正在检索知识库",
  "elapsedMs": null,
  "details": {
    "knowledgeBaseCount": 3,
    "knowledgeBases": [
      {"knowledgeBaseId": "kb-1", "name": "政策知识库"},
      {"knowledgeBaseId": "kb-2", "name": "业务规则知识库"},
      {"knowledgeBaseId": "kb-3", "name": "技术文档知识库"}
    ],
    "queryIndex": 1,
    "queryCount": 3
  }
}
```

完成：

```text
event: rag_progress
data: {
  "eventId": "retrieval-1",
  "sequence": 6,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:01.032Z",
  "stage": "retrieval",
  "status": "completed",
  "title": "知识库检索完成",
  "elapsedMs": 512,
  "details": {
    "knowledgeBaseCount": 3,
    "knowledgeBases": [
      {"knowledgeBaseId": "kb-1", "name": "政策知识库"},
      {"knowledgeBaseId": "kb-2", "name": "业务规则知识库"},
      {"knowledgeBaseId": "kb-3", "name": "技术文档知识库"}
    ],
    "candidateCount": 42,
    "resultCount": 5,
    "queryIndex": 1,
    "queryCount": 3
  }
}
```

当 query rewrite 产生多个查询时，每次检索使用不同的 `eventId`，并通过 `queryIndex` / `queryCount` 表达进度。

第一版向前台推送本轮检索知识库的稳定摘要，包括知识库 ID 和名称，但不推送单库开始、结束或耗时事件。多库并行任务的完成顺序不稳定，直接推送单库过程会造成界面跳动。

### 5.3 Rerank

开始：

```text
event: rag_progress
data: {
  "eventId": "rerank-global-1",
  "sequence": 7,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:01.040Z",
  "stage": "rerank",
  "status": "started",
  "title": "正在对检索结果重排序",
  "elapsedMs": null,
  "details": {
    "scope": "global",
    "inputCount": 42
  }
}
```

完成：

```text
event: rag_progress
data: {
  "eventId": "rerank-global-1",
  "sequence": 8,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:01.208Z",
  "stage": "rerank",
  "status": "completed",
  "title": "检索结果重排序完成",
  "elapsedMs": 168,
  "details": {
    "scope": "global",
    "inputCount": 42,
    "outputCount": 5
  }
}
```

`scope` 使用稳定枚举：

| 值 | 说明 |
| --- | --- |
| `knowledge_base` | 单知识库内部 rerank |
| `global` | 一次检索内的全局 rerank |
| `rewrite_merge` | 多个改写查询结果合并后的 rerank |

普通前台建议只展示 `global` 和 `rewrite_merge`。单库 rerank 可以记录，但默认不展示。

### 5.4 LLM 交互与首 token

LLM 开始：

```text
event: rag_progress
data: {
  "eventId": "answer-generation-1",
  "sequence": 9,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:01.220Z",
  "stage": "answer_generation",
  "status": "started",
  "title": "正在生成答案",
  "elapsedMs": null,
  "details": {}
}
```

首 token：

```text
event: rag_progress
data: {
  "eventId": "answer-generation-1",
  "sequence": 10,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:01.940Z",
  "stage": "answer_generation",
  "status": "milestone",
  "title": "答案开始生成",
  "elapsedMs": 720,
  "details": {
    "milestone": "first_token"
  }
}
```

首个 `delta` 应紧随 `first_token` 事件发送。

LLM 完成：

```text
event: rag_progress
data: {
  "eventId": "answer-generation-1",
  "sequence": 26,
  "traceId": "4f7c2d9a0c0b4c69a321ad11bcdef123",
  "occurredAt": "2026-06-03T06:10:04.080Z",
  "stage": "answer_generation",
  "status": "completed",
  "title": "答案生成完成",
  "elapsedMs": 2860,
  "details": {
    "finishReason": "stop",
    "promptTokens": 3200,
    "completionTokens": 680,
    "totalTokens": 3880
  }
}
```

说明：

- `first_token` 实际上是第一个非空 `delta`，不依赖模型是否真正按 token 分片。
- token usage 仅在模型返回 usage 时发送，不做字符数估算。
- 模型 ID 默认不向普通前台展示，但可以保留在服务端 Trace 中。

### 5.5 完整 SSE 顺序示例

```text
event: rag_progress  stage=query_rewrite status=started
event: rag_progress  stage=query_rewrite status=completed
event: rag_progress  stage=retrieval status=started
event: rag_progress  stage=rerank status=started
event: rag_progress  stage=rerank status=completed
event: rag_progress  stage=retrieval status=completed
event: rag_progress  stage=answer_generation status=started
event: rag_progress  stage=answer_generation status=milestone milestone=first_token
event: delta
event: delta
event: ...
event: reference
event: rag_progress  stage=answer_generation status=completed
event: done
```

## 6. 服务端架构设计

### 6.1 不让下层模块依赖 SSE

错误做法：

```text
retrieval-engine -> SseEmitter
```

`rag-retrieval-engine` 不应依赖 Web 层对象，否则：

- 同步 answer 和 retrieval API 难以复用检索引擎。
- 检索引擎被绑定到 SSE 传输协议。
- 单元测试复杂度上升。
- 后续改为 WebSocket 或消息队列时需要重写下层模块。

建议引入与传输无关的流程事件端口：

```java
public interface RagProcessEventPublisher {

    StageHandle start(RagProcessStage stage, Map<String, Object> details);

    void milestone(StageHandle handle, String milestone, Map<String, Object> details);

    void complete(StageHandle handle, Map<String, Object> details);

    void fallback(StageHandle handle, Map<String, Object> details);

    void fail(StageHandle handle, String errorCode);
}
```

阶段枚举：

```java
public enum RagProcessStage {
    QUERY_REWRITE,
    RETRIEVAL,
    RERANK,
    ANSWER_GENERATION
}
```

`StageHandle` 保存：

- `eventId`
- `stage`
- `startNanoTime`

`occurredAt` 使用系统时钟表示事件发生时间；结束事件的 `elapsedMs` 必须由服务端使用单调时钟计算，不能通过两个 `occurredAt` 相减，也不能由前台自行计算。

### 6.2 Publisher 实现

建议提供两个实现：

| 实现 | 用途 |
| --- | --- |
| `NoOpRagProcessEventPublisher` | 同步 answer、普通 retrieval API、未开启流程事件的请求 |
| `SseRagProcessEventPublisher` | SSE answer 请求，将领域事件转换为 `rag_progress` |

这样同步接口不需要判断 `eventConsumer == null`，业务代码可以始终调用 publisher。

### 6.3 Publisher 的传递方式

不建议使用全局静态变量或 `ThreadLocal` 保存 publisher。当前代码使用虚拟线程和 Reactor，`ThreadLocal` 容易在跨线程时丢失。

建议显式传递请求级上下文：

```java
public record RagAnswerExecutionContext(
        RagProcessEventPublisher eventPublisher
) {}
```

调用关系：

```text
RagController
  -> RagApiService.streamAnswer(request, publisher)
      -> prepareAnswer(request, executionContext)
          -> query rewrite
          -> retrievalEngine.execute(plan, executionContext)
              -> RetrievalExecutionService
                  -> rerank
      -> llmService.streamGenerate(...)
```

如果不希望修改公共 `RetrievalEngine` 接口，可以在 `rag-core-common` 中定义一个更通用的执行监听器，或在检索请求模型中增加可选的内部执行上下文。不要将 `SseEmitter`、`Consumer<StreamEvent>` 放入公共领域模型。

### 6.4 与 Trace 的关系

建议流程事件发布器从当前 tracing 上下文获取 `traceId`，写入每个 `RagProgressEvent`。

流程事件和 Trace 可以在相同阶段边界发布，但应保持解耦：

```text
阶段开始
  -> start Observation / Span
  -> publish rag_progress started

阶段结束
  -> publish rag_progress completed
  -> stop Observation / Span
```

可以进一步封装为统一的 `RagStageExecution` 辅助组件，减少遗漏开始或结束事件的风险。

## 7. 各代码层的改造点

### 7.1 `ApiModels`

新增：

```java
RagProgressEvent
```

保留：

```java
RagAnswerStreamDelta
RagAnswerStreamDone
RagAnswerStreamError
```

### 7.2 `RagController`

职责保持不变：

- 创建 `SseEmitter`
- 将统一事件写入 SSE
- 处理连接中断
- 完成或关闭 emitter

需要增加：

- 创建请求级 `SseRagProcessEventPublisher`
- 为所有事件分配单调递增 `sequence`
- 将 `rag_progress`、`delta`、`done`、`error` 通过同一个串行写入器发送

### 7.3 `RagApiService`

主要改造点：

- `rewriteQuery()` 前后发布 `query_rewrite`
- `executeRetrievalWithRewrite()` 为每次检索发布 `retrieval`
- 多次检索结果合并后的 rerank 发布 `rerank(scope=rewrite_merge)`
- `streamGenerate()` 前发布 `answer_generation started`
- 第一个非空 `delta` 前发布 `answer_generation milestone(first_token)`
- 完成块到达时发布 `answer_generation completed`

### 7.4 `rag-retrieval-engine`

需要发布：

- 多知识库检索的开始和结束摘要
- 全局 rerank 开始和结束
- 可选的单知识库 rerank 事件

第一版建议由检索引擎只向上返回结构化执行摘要，再由 `RagApiService` 推送用户可见事件。原因是当前 `RetrievalTrace` 已经包含：

- embedding 耗时
- 多库召回耗时
- 单库 rerank 耗时
- 全局 rerank 耗时
- 候选数和结果数

但是这种“执行完成后统一推送”的方式无法实时发送 rerank `started`。如果前台明确需要实时看到 rerank 开始事件，就必须将 `RagProcessEventPublisher` 或更通用的执行监听器传入检索引擎。

建议分两步实施：

1. 第一阶段由 `RagApiService` 推送 query rewrite、retrieval、answer generation，快速改善等待体验。
2. 第二阶段将通用执行监听器传入检索引擎，实时推送 rerank 开始和结束。

## 8. 并发、顺序与可靠性

### 8.1 所有 SSE 写入必须串行化

多知识库检索使用虚拟线程，LLM 流式回调可能来自 Reactor 线程。多个线程不能直接同时调用 `SseEmitter.send()`。

建议使用请求级串行事件分发器：

```text
业务线程 / 虚拟线程 / Reactor 回调
  -> enqueue(StreamEvent)
      -> 单一发送线程
          -> SseEmitter.send()
```

分发器负责：

- 分配 `sequence`
- 保证发送顺序
- 序列化调用 `SseEmitter.send()`
- 处理客户端断开
- 防止 `done` 后继续发送事件

### 8.2 顺序规则

必须满足：

1. 同一阶段 `started` 先于 `completed` / `fallback` / `failed`。
2. `answer_generation` 的 `first_token` 先于首个 `delta`。
3. `answer_generation completed` 先于 `done`。
4. `done` 或 `error` 是最后一个事件。
5. 并行阶段之间只保证 `sequence` 顺序，不保证业务完成顺序。

### 8.3 背压和事件数量

流程事件数量应保持较少，不能为每个 token、每个 chunk、每个候选文档发送流程事件。

建议单次 answer 请求的流程事件控制在 20 个以内。`delta` 事件仍按现有逻辑发送。

如果前台消费过慢：

- `delta` 不能静默丢弃，否则答案内容不完整。
- `rag_progress` 可以在队列压力较大时合并非关键重复事件，但第一版可以不实现。
- 队列达到上限后应终止请求并发送或记录连接异常，不能无限占用内存。

### 8.4 客户端断开

当 `SseEmitter.send()` 抛出异常时：

- 标记请求已取消。
- 停止继续发送流程事件和 `delta`。
- 尽可能取消 LLM 流式订阅和后续任务。
- 不将客户端断开误报为 RAG 业务失败。

## 9. 错误、降级与跳过语义

### 9.1 主流程失败

如果阶段失败且请求无法继续：

```text
rag_progress status=failed
error
```

例如检索服务完全不可用：

```text
event: rag_progress
data: {
  "stage": "retrieval",
  "status": "failed",
  "title": "知识库检索失败",
  "elapsedMs": 520,
  "details": {
    "errorCode": "RETRIEVAL_FAILED"
  }
}

event: error
data: {
  "code": "RETRIEVAL_FAILED",
  "message": "知识检索失败"
}
```

不要向前台发送内部异常消息、URL、密钥或堆栈。

### 9.2 可降级失败

Query rewrite 失败后会使用原始 query 继续检索，因此发送：

```text
status=fallback
```

而不是：

```text
status=failed
```

### 9.3 阶段跳过

如果请求未开启 query rewrite 或没有 rerank 配置，可以选择：

- 不发送任何事件，界面更简洁。
- 在调试模式下发送 `skipped`。

普通用户界面建议不发送 `skipped`。

## 10. 前台处理建议

### 10.1 展示方式

前台可以将 `rag_progress` 展示为步骤列表：

```text
✓ 已识别问题意图                         120 ms
✓ 已改写检索问题                         385 ms
✓ 已完成知识库检索                       512 ms
✓ 已完成结果重排序                       168 ms
● 正在生成答案
```

收到首个 `delta` 后：

- 保留已完成步骤。
- 将答案区域切换为流式输出。
- 可以收起流程详情，避免干扰阅读。

### 10.2 前台状态管理

前台以 `eventId` 为键维护阶段实例：

```javascript
const stagesById = new Map();

function onRagProgress(event) {
  stagesById.set(event.eventId, event);
  render([...stagesById.values()].sort((a, b) => a.sequence - b.sequence));
}
```

不要只以 `stage` 为键，因为 query rewrite 可能产生多次 `retrieval`。

### 10.3 前台兼容性

前台必须：

- 忽略未知 `stage`
- 忽略未知 `status`
- 忽略未知 `details` 字段
- 不依赖 `title` 作为业务判断条件
- 使用 `stage` 和 `status` 驱动状态

## 11. 配置与灰度

建议增加服务端配置：

```yaml
app:
  rag:
    answer:
      stream-progress:
        enabled: true
        include-trace-id: true
        include-token-usage: true
        include-knowledge-base-details: false
```

第一版默认策略：

- 所有 SSE answer 请求默认发送 `rag_progress`。
- 旧前台继续只处理 `delta`、`done`、`error`，忽略未知的 `rag_progress` 事件。
- 当前不增加请求级开关，避免为短期灰度能力扩展请求协议；如后续确有灰度需求，优先增加服务端配置开关。

## 12. 分阶段实施计划

### 阶段一：协议和基础分发器

目标：在不影响旧前台的前提下支持 `rag_progress`。

任务：

1. 新增 `RagProgressEvent` DTO。
2. 新增 `rag_progress` SSE event name。
3. 新增请求级串行事件分发器，统一发送 `rag_progress`、`delta`、`done`、`error`。
4. 为事件增加 `eventId`、`sequence`、`traceId`、`occurredAt`。
5. 保持旧前台忽略未知 SSE 事件时的兼容性。

验收：

- 旧前台仍能正常接收答案。
- 新前台可以收到有序的 `rag_progress`。
- `done` / `error` 后不会继续发送事件。

### 阶段二：接入应用层关键阶段

目标：快速改善首个 token 前的等待体验。

任务：

1. 接入 `query_rewrite`，支持 `completed` 和 `fallback`。
2. 接入每次 `retrieval` 的开始和结束。
3. 接入 `answer_generation` 开始、首 token、完成。
4. 在完成事件中发送安全的结果摘要和耗时。

验收：

- 前台在首个 `delta` 前可以持续展示流程进度。
- Query rewrite 失败降级时不会展示为整个请求失败。
- `first_token` 事件严格先于首个 `delta`。

### 阶段三：接入检索引擎内部事件

目标：实时展示 rerank 等检索内部关键阶段。

任务：

1. 定义与传输无关的 `RagProcessEventPublisher` 或执行监听器。
2. 将请求级发布器传入检索执行上下文。
3. 接入全局 rerank 和 rewrite merge rerank。
4. 接入单知识库 rerank。
5. 保证并行线程发布事件时仍由串行分发器发送。

验收：

- 前台可以实时看到 rerank 开始和结束。
- 多知识库并行不会导致 SSE 并发写入异常。
- 检索引擎不依赖 `SseEmitter` 或 Web 层类。

### 阶段四：完善取消、测试和观测

目标：保证生产环境中的协议稳定性和资源安全。

任务：

1. 处理客户端断开和任务取消。
2. 增加 SSE 协议集成测试。
3. 增加 started / completed 配对测试。
4. 增加 query rewrite fallback、检索失败、LLM 失败测试。
5. 增加事件数量、队列长度和发送失败监控。
6. 将 `traceId` 与 OpenTelemetry Trace 对齐。

验收：

- 客户端断开后不会继续产生大量事件或 LLM 输出。
- 所有正常完成阶段都有匹配的结束事件。
- 前台收到的 `traceId` 可以用于查询完整 Trace。

## 13. 测试清单

至少覆盖以下场景：

| 场景 | 预期事件 |
| --- | --- |
| 无 query rewrite、无 rerank | retrieval -> answer generation -> delta -> done |
| 开启 query rewrite | query rewrite started / completed，多次 retrieval |
| Query rewrite 失败降级 | query rewrite fallback，主流程继续 |
| 单库检索 | retrieval started / completed |
| 多库检索 | retrieval details 中包含 knowledgeBaseCount 和 knowledgeBases 名称摘要 |
| 全局 rerank | rerank started / completed |
| rewrite merge rerank | rerank scope=`rewrite_merge` |
| 首 token | milestone(first_token) 先于首个 delta |
| LLM 正常结束 | answer generation completed 先于 done |
| 检索失败 | retrieval failed，随后 error |
| LLM 失败 | answer generation failed，随后 error |
| 客户端断开 | 停止继续发送，任务尽可能取消 |
| 旧前台 | 忽略 rag_progress，答案仍完整 |

## 14. 关键设计决策

1. **只新增一个 `rag_progress` SSE 事件类型**，阶段和状态放在 payload 中。
2. **流程事件是稳定的前台协议，不是内部 Trace Span 的直接映射**。
3. **下层模块不依赖 `SseEmitter`**，通过请求级发布接口或执行监听器解耦。
4. **所有 SSE 写入必须串行化**，避免虚拟线程和 Reactor 回调并发调用 emitter。
5. **耗时由服务端使用单调时钟计算**，前台不自行计算。
6. **第一版只推送用户可理解的重要阶段**，单知识库 rerank 只发送阶段摘要，不发送知识库 ID、路由或候选文档内容。
7. **Query rewrite 失败使用 `fallback`，而不是 `failed`**。
8. **首 token 使用 `milestone(first_token)`，并严格先于首个 `delta`**。
9. **保留 `delta/done/error` 兼容性**，旧前台通过忽略未知的 `rag_progress` 事件继续工作。
10. **流程事件携带 TraceID，但不替代 OpenTelemetry Trace**。
