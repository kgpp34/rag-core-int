# RAG Trace 异步持久化设计方案

## 背景

当前项目已有两类与 trace 相关的能力：

- HTTP 请求级 traceId：由 `TraceLoggingFilter` 写入 MDC，并通过 `X-Trace-Id` 贯穿请求。
- 检索调试 trace：由 `RetrievalDebugTraceWriter` 在检索链路中同步写入 JSONL 文件，覆盖 retrieval request、query preprocess、embedding、Milvus dense/sparse request/response、单库召回、rerank、全局排序等事件。

后续需要把完整 RAG 链路事件异步写入数据库，用于排查检索效果、复盘 query 改写、多路召回、rerank 和答案生成过程。

## 目标

1. 抽取独立 `rag-trace` 模块，作为 trace bounded context，不把持久化逻辑继续放在 `rag-retrieval-engine`。
2. 以 DDD 分层组织 trace 模块，保持领域模型、应用编排、基础设施实现的边界清晰。
3. trace 写库必须异步执行，不阻塞 RAG 主链路。
4. trace 表必须写入主业务库，也就是 `spring.datasource` 指向的库，和 `SPRING_AI_CHAT_MEMORY`、`t_rag_conversation_summary` 在同一个库。
5. trace 模块不得连接、写入或污染 Dify 元数据库 `rag.datasource.metadata.dify`。
6. 完整记录检索链路事件，包括 query 改写、规划、检索执行、embedding、dense/sparse 召回、融合、单库 rerank、全局 rerank、最终结果、答案生成。
7. 保留现有 JSONL 调试能力，可通过 sink 配置与 JDBC sink 并存。
8. trace 监控不能显著影响检索路径性能。业务线程只允许做轻量对象构造和无阻塞投递，禁止在检索线程执行 JDBC、文件 IO、长耗时 JSON 序列化或阻塞等待。

## 非目标

1. 第一阶段不提供 trace 查询 API 或后台页面。
2. 第一阶段不引入 Kafka、MQ、OpenTelemetry Collector 等外部依赖。
3. 第一阶段不把检索事件拆成多张强业务表，先使用通用事件表承载 JSON payload。
4. 不在 Dify 库创建任何表，不复用 Dify 的 MyBatis mapper 或 `difyDataSource`。

## 模块边界

新增 Maven 模块：

```text
rag-trace
```

模块依赖建议：

```text
rag-core-common
        ^
        |
rag-trace
        ^
        |
app / rag-retrieval-engine / rag-query-planner
```

`rag-trace` 可以依赖 `rag-core-common` 中的基础异常或通用类型，但不得依赖 `app`、`rag-retrieval-engine`、`rag-query-planner`、`rag-metadata-cacher`。

接入方只依赖 `rag-trace` 提供的应用端口，例如 `TraceRecorder`。接入方负责把自身领域对象转换为 trace payload，trace 模块不理解 retrieval 领域细节。

## DDD 分层设计

建议目录：

```text
rag-trace/src/main/java/com/cffex/rag/trace
  domain/
    TraceEvent.java
    TraceEventId.java
    TraceSessionId.java
    TraceStage.java
    TraceSource.java
    TraceWritePolicy.java
    TraceRepository.java
    TraceSink.java
  application/
    TraceRecorder.java
    DefaultTraceRecorder.java
    AsyncTraceWriteService.java
    TraceBatchWriter.java
  infrastructure/
    jdbc/
      JdbcTraceRepository.java
      TraceEventRowMapper.java
    file/
      JsonlTraceSink.java
    config/
      TraceProperties.java
      TraceAutoConfiguration.java
```

### Domain

核心领域对象：

```java
public record TraceEvent(
        String traceId,
        String requestId,
        String source,
        String stage,
        String eventName,
        Instant occurredAt,
        Map<String, Object> payload
) {}
```

字段含义：

- `traceId`：跨 HTTP 请求贯穿的 traceId，优先来自 MDC `traceId`。
- `requestId`：某次 retrieval/answer 内部请求 ID，可为空。一次 HTTP 请求内 query rewrite 可能触发多次 retrieval，每次 retrieval 有自己的 requestId。
- `source`：事件来源，例如 `app`、`query-planner`、`retrieval-engine`、`milvus`、`llm`。
- `stage`：粗粒度阶段，例如 `query_rewrite`、`planning`、`retrieval`、`embedding`、`recall`、`rerank`、`answer_generation`。
- `eventName`：细粒度事件名，例如 `kb.dense.raw`、`global.final`。
- `occurredAt`：事件发生时间。
- `payload`：结构化事件内容。

领域端口：

```java
public interface TraceRepository {
    void saveBatch(List<TraceEvent> events);
}

public interface TraceSink {
    void write(List<TraceEvent> events);
}
```

`TraceRepository` 面向数据库持久化，`TraceSink` 面向多目标输出。JDBC sink、JSONL sink 都可以实现 `TraceSink`。

### Application

应用服务：

```java
public interface TraceRecorder {
    void record(TraceEvent event);
}
```

`DefaultTraceRecorder` 只负责接收事件并投递给异步队列，不执行 JDBC 写入。

`AsyncTraceWriteService` 负责：

- 有界队列。
- 按 `batch-size` 或 `flush-interval-ms` 批量刷写。
- 多 sink fan-out。
- 溢出策略。
- 写入失败处理。
- 应用关闭时 drain 队列。

### Infrastructure

`JdbcTraceRepository` 使用 Spring 默认 `JdbcTemplate`：

```java
JdbcTraceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper)
```

这里的 `JdbcTemplate` 必须来自主业务库 `spring.datasource`。不得通过 `@Qualifier("difyDataSource")`、`difySqlSessionFactory` 或 metadata cacher mapper 获取连接。

`JsonlTraceSink` 复用现有 JSONL 文件能力，作为可选 sink。

## 数据源约束

当前项目数据源语义：

- 主业务库：`spring.datasource`，当前用于 `SPRING_AI_CHAT_MEMORY` 和 `t_rag_conversation_summary`。
- Dify 元数据库：`rag.datasource.metadata.dify`，只用于读取 Dify metadata。

trace 持久化必须绑定主业务库：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/rag
```

禁止：

- 在 Dify 库创建 `t_rag_trace_event`。
- 在 `rag-trace` 模块读取 `rag.datasource.metadata.dify.*`。
- 在 `rag-trace` 模块依赖 `rag-metadata-cacher`。
- 在 trace repository 中注入 `difyDataSource` 或 Dify MyBatis mapper。

实现时建议补一个 Spring context 测试：同时存在主 datasource 和 Dify datasource 时，`JdbcTraceRepository` 使用默认 `JdbcTemplate`，写入表位于主业务库。

## 配置设计

建议使用新的配置前缀，避免继续挂在 `retrieval.debug-trace` 下：

```yaml
rag:
  trace:
    enabled: true
    mode: summary
    include-sensitive-payload: false
    text-preview-length: 200
    max-payload-bytes: 65536
    max-candidates-per-event: 20
    sample-rate: 1.0
    sinks:
      jdbc:
        enabled: true
        queue-capacity: 10000
        batch-size: 100
        flush-interval-ms: 1000
        shutdown-timeout-ms: 5000
        overflow-policy: drop_oldest
        offer-timeout-ms: 0
      file:
        enabled: false
        output-dir: /tmp/retrieval-traces/rag-core-int
```

`mode`：

- `off`：完全关闭 trace。
- `summary`：默认模式，只记录关键摘要、计数、耗时、topN 候选 preview/hash。
- `detail`：记录完整诊断事件，但仍遵守 payload 大小和候选数上限。
- `debug`：仅本地排障使用，可打开更完整 payload，例如 embedding vector。

溢出策略：

- `drop_oldest`：推荐默认值。数据库慢时保留较新的诊断信息。
- `drop_newest`：保留已有队列，丢弃新事件。
- `block`：强一致但会反压 RAG 请求，生产环境禁止默认启用。

敏感信息策略：

- 默认不记录 auth token。
- 默认不记录完整 embedding vector，只记录 dim、norm、hash、firstN。
- 大文本默认使用 preview 和 hash，必要时通过配置打开完整 payload。

性能保护开关：

- `max-candidates-per-event`：限制每个事件最多记录多少候选，避免 topK/candidateK 较大时 payload 暴涨。
- `max-payload-bytes`：序列化前估算或序列化后截断超大 payload，超限时保留摘要并标记 `payloadTruncated=true`。
- `sample-rate`：按 traceId 或 requestId 稳定采样。生产高 QPS 时可以只记录部分请求的 detail。
- `offer-timeout-ms=0`：业务线程对队列执行无等待 offer，失败立即按溢出策略降级。

## 性能隔离设计

trace 是诊断能力，不属于检索正确性的关键路径。设计上必须遵守以下原则：

1. 业务线程不执行数据库写入。
2. 业务线程不执行文件写入。
3. 业务线程不等待 trace 队列有空间。
4. 业务线程不因 trace 写入失败而抛出异常。
5. 业务线程只构造轻量 payload；大对象摘要、候选截断、脱敏和 JSON 序列化尽量放到后台 worker。
6. trace 队列满、数据库慢、数据库不可用、JSONL 文件不可写时，只影响 trace 完整性，不影响检索结果。

### 业务线程成本控制

`TraceRecorder.record` 在检索线程中只做：

- 判断 `enabled/mode/sample`。
- 补齐 `traceId/requestId/source/stage/eventName/occurredAt`。
- 对候选列表做最多 `max-candidates-per-event` 的浅层截断。
- 把事件对象 `offer` 到内存队列。

不做：

- JDBC。
- 文件 IO。
- 网络 IO。
- 阻塞等待。
- 对完整 payload 做大规模 JSON 序列化。
- 对完整 chunk content、prompt、answer 做重复 hash，除非已经在调用点可低成本获得。

### 事件 payload 分级

为了避免完整链路事件影响检索性能，事件分为两层：

- summary payload：计数、耗时、模型 ID、知识库 ID、topN 候选 preview/hash。生产默认使用。
- detail payload：请求体描述、候选列表、metadata、LLM 改写响应等。仅在 detail/debug 模式或采样命中时记录。

对于候选列表类事件，默认只记录 topN：

```text
recordedCandidateCount <= max-candidates-per-event
originalCandidateCount = 实际候选数量
candidateListTruncated = originalCandidateCount > recordedCandidateCount
```

### 队列与降级

队列必须是有界队列。推荐默认：

```yaml
queue-capacity: 10000
overflow-policy: drop_oldest
offer-timeout-ms: 0
```

当队列满时：

- `drop_oldest`：丢弃最旧事件，再尝试放入新事件。
- `drop_newest`：丢弃当前事件。
- `block`：仅允许本地调试或离线压测，不建议生产使用。

所有丢弃行为只记录计数器和限频 warn 日志，不能影响检索请求。

### 数据库隔离

JDBC sink 使用独立后台线程池，不使用检索执行线程。批量写入参数独立配置：

- `batch-size`
- `flush-interval-ms`
- `shutdown-timeout-ms`

如主库连接池压力较大，后续可以为 trace 增加同一主库 URL 下的独立小连接池，但仍必须连接 `spring.datasource` 同一个业务库，不能切到 Dify 库。

### 性能指标

trace 模块应暴露内部指标，至少包括：

- `rag_trace_queue_size`
- `rag_trace_queue_capacity`
- `rag_trace_events_enqueued_total`
- `rag_trace_events_written_total`
- `rag_trace_events_dropped_total`
- `rag_trace_write_failures_total`
- `rag_trace_batch_write_duration_ms`
- `rag_trace_record_duration_ns`

日志限频输出：

```text
trace队列状态 | queueSize={}, dropped={}, writeFailures={}, lastWriteMs={}
```

### 性能验收标准

实现完成后需要做对比压测：

- trace 关闭。
- trace summary 模式开启，JDBC sink 正常。
- trace summary 模式开启，模拟数据库慢或不可用。
- trace detail 模式开启。

建议验收门槛：

- summary 模式下，检索接口 p95 延迟增幅不超过 3%。
- summary 模式下，检索接口 p99 延迟增幅不超过 5%。
- 数据库不可用时，检索请求成功率不受 trace 影响。
- 数据库不可用时，业务线程不出现等待 JDBC 的堆栈。
- 队列满时，检索请求不阻塞，只增加 dropped 计数。

如果压测不满足标准，应优先降低默认事件粒度、减小候选 payload、启用采样，而不是增加业务线程等待。

## 默认生产策略

生产环境建议默认：

```yaml
rag:
  trace:
    enabled: true
    mode: summary
    include-sensitive-payload: false
    text-preview-length: 120
    max-candidates-per-event: 10
    max-payload-bytes: 32768
    sample-rate: 1.0
    sinks:
      jdbc:
        enabled: true
        queue-capacity: 10000
        batch-size: 100
        flush-interval-ms: 1000
        overflow-policy: drop_oldest
        offer-timeout-ms: 0
      file:
        enabled: false
```

排查疑难问题时，可以短时间把特定环境切到 `detail`，或通过后续扩展支持按 `traceId`、用户、接口、知识库进行定向采样。

## 数据库表设计

第一阶段使用单事件表：

```sql
CREATE TABLE IF NOT EXISTS t_rag_trace_event (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(128),
    source VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    event_name VARCHAR(128) NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rag_trace_event_trace_time
ON t_rag_trace_event(trace_id, event_time);

CREATE INDEX IF NOT EXISTS idx_rag_trace_event_request_time
ON t_rag_trace_event(request_id, event_time);

CREATE INDEX IF NOT EXISTS idx_rag_trace_event_stage_time
ON t_rag_trace_event(stage, event_time);

CREATE INDEX IF NOT EXISTS idx_rag_trace_event_name_time
ON t_rag_trace_event(event_name, event_time);
```

DDL 应与 `SPRING_AI_CHAT_MEMORY` 所在库的迁移或初始化脚本放在同一套主业务库初始化流程中。

后续可选 summary 表：

```sql
CREATE TABLE IF NOT EXISTS t_rag_trace_session (
    trace_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    query_text TEXT,
    status VARCHAR(32) NOT NULL,
    total_ms BIGINT,
    candidate_count INTEGER,
    result_count INTEGER,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (trace_id, request_id)
);
```

第一阶段不强制实现 `t_rag_trace_session`。如果后续需要快速按请求查摘要，再基于事件流补充。

## 异步写入流程

```text
业务线程
  -> TraceRecorder.record(event)
  -> 校验 enabled
  -> 标准化 traceId/source/stage/eventName/occurredAt
  -> offer 到 BlockingQueue
  -> 立即返回

后台 worker
  -> drain queue
  -> 满 batch-size 或到 flush-interval-ms
  -> fan-out 到 enabled sinks
  -> JdbcTraceRepository.saveBatch(events)
  -> JsonlTraceSink.write(events)
```

失败处理：

- 单批 JDBC 写入失败，记录 warn/error，不向业务线程抛出异常。
- 可以做 1 次短暂重试，避免瞬时连接抖动。
- 重试仍失败则丢弃该批，递增失败计数。
- 定期日志输出队列长度、成功写入数、失败数、丢弃数。

关闭处理：

- `AsyncTraceWriteService` 实现 `SmartLifecycle` 或 `DisposableBean`。
- 应用关闭时停止接收新事件。
- 在 `shutdown-timeout-ms` 内 drain 队列。
- 超时仍未写完时记录剩余事件数。

## Trace 与 SSE 事件关系

现有 `RagProcessEventPublisher` 面向用户可见的进度事件，主要服务 SSE：

- `QUERY_REWRITE`
- `RETRIEVAL`
- `RERANK`
- `ANSWER_GENERATION`

新的 `rag-trace` 面向持久化诊断，不替代 SSE。两者关系如下：

- SSE 事件：少量、面向用户、阶段级、可读性优先。
- Trace 事件：完整、面向诊断、细粒度、结构化 payload 优先。

实现时可以新增一个组合 publisher：

```text
CompositeRagProcessEventPublisher
  -> SseRagProcessEventPublisher
  -> TraceRagProcessEventPublisher
```

`TraceRagProcessEventPublisher` 将 `start`、`milestone`、`complete`、`fallback`、`fail` 转换为 trace 事件。这样 query 改写、检索、rerank、答案生成这些 app 编排层事件可以自动入库。

底层 retrieval 详细事件仍通过 `RetrievalDebugTraceWriter` 适配到 `TraceRecorder`。

## 检索链路事件清单

### API 入口

| eventName | stage | source | 触发点 | payload 要点 |
| --- | --- | --- | --- | --- |
| `rag.request.received` | `request` | `app` | `search` / `answer` / `streamAnswer` 开始 | endpointType、queryLength、docFilterCount、planType、queryRewriteEnabled、stream |
| `rag.request.completed` | `request` | `app` | 请求正常结束 | resultCount、finishReason、totalMs |
| `rag.request.failed` | `request` | `app` | 请求异常 | errorCode、message、totalMs |

### 查询规划

| eventName | stage | source | 触发点 | payload 要点 |
| --- | --- | --- | --- | --- |
| `planning.request` | `planning` | `app` | 调用 `queryPlannerFacade.plan` 前 | planType、domainCode、docFilterCount、tuning |
| `planning.completed` | `planning` | `query-planner` | 生成 `ExecutionPlan` 后 | retrievalPlanCount、primaryKbCount、topK、rankingMode、embeddingModelId、rerankModelId |
| `planning.failed` | `planning` | `app` | 规划异常 | errorCode、message |

### Query 改写

| eventName | stage | source | 触发点 | payload 要点 |
| --- | --- | --- | --- | --- |
| `query_rewrite.started` | `query_rewrite` | `app` | `rewriteQuery` 开始 | originalQueryHash、originalQueryPreview、promptHash、modelId |
| `query_rewrite.llm.request` | `query_rewrite` | `llm` | 调用 LLM 前 | modelId、temperature、maxTokens、promptPreview |
| `query_rewrite.llm.response` | `query_rewrite` | `llm` | LLM 返回后 | responseHash、responsePreview、finishReason、usage |
| `query_rewrite.completed` | `query_rewrite` | `app` | 解析子 query 后 | queryCount、subQueriesPreview、subQueriesHash |
| `query_rewrite.fallback` | `query_rewrite` | `app` | 改写失败降级 | reason、queryCount=1 |

### 检索请求与多 query 编排

| eventName | stage | source | 触发点 | payload 要点 |
| --- | --- | --- | --- | --- |
| `retrieval.request` | `retrieval` | `retrieval-engine` | `DefaultRetrievalEngine.execute` 开始 | requestId、queryPreview、topK、recallSpecCount、rankingMode、globalRerankEnabled、knowledgeBaseRerankCount |
| `retrieval.started` | `retrieval` | `app` | `executeRetrieval` 开始 | queryIndex、queryCount、knowledgeBaseCount、knowledgeBases |
| `retrieval.completed` | `retrieval` | `app` | 单次 retrieval 结束 | queryIndex、queryCount、resultCount、elapsedMs |
| `retrieval.failed` | `retrieval` | `app` | 单次 retrieval 失败 | errorCode、queryIndex、queryCount |
| `retrieval.rewrite_merge.started` | `retrieval` | `app` | 多 query 结果 merge 前 | queryCount、inputResultCount |
| `retrieval.rewrite_merge.completed` | `retrieval` | `app` | merge/rerank 后 | mergedCount、finalCount、rerankApplied |

### Query 预处理与 embedding

| eventName | stage | source | 触发点 | payload 要点 |
| --- | --- | --- | --- | --- |
| `query.preprocess` | `retrieval` | `retrieval-engine` | `RetrievalExecutionService` query preprocess 后 | rawQueryPreview、effectiveQueryPreview、queryRewritten、elapsedMs |
| `embedding.query` | `embedding` | `retrieval-engine` | query embedding 后 | model、endpoint、inputHash、elapsedMs、vectorSummary |
| `embedding.skipped` | `embedding` | `retrieval-engine` | 无 dense route 时 | reason、recallModes |

### Milvus dense/sparse 路由

| eventName | stage | source | 触发点 | payload 要点 |
| --- | --- | --- | --- | --- |
| `milvus.dense.request` | `recall` | `milvus` | dense search 前 | knowledgeBaseId、engineId、collectionName、candidateK、docFilterCount、queryVectorSummary |
| `milvus.dense.response` | `recall` | `milvus` | dense search 后 | knowledgeBaseId、rawCount、count、scoreThreshold、candidates |
| `milvus.sparse.request` | `recall` | `milvus` | sparse search 前 | knowledgeBaseId、engineId、collectionName、candidateK、docFilterCount、queryTextPreview |
| `milvus.sparse.response` | `recall` | `milvus` | sparse search 后 | knowledgeBaseId、rawCount、count、scoreThreshold、candidates |
| `milvus.dense.failed` | `recall` | `milvus` | dense search 异常 | knowledgeBaseId、collectionName、error |
| `milvus.sparse.failed` | `recall` | `milvus` | sparse search 异常 | knowledgeBaseId、collectionName、error |

### 单知识库召回闭环

| eventName | stage | source | 触发点 | payload 要点 |
| --- | --- | --- | --- | --- |
| `kb.recall.started` | `recall` | `retrieval-engine` | `KnowledgeBaseRetrievalTask.execute` 开始 | knowledgeBaseId、retrievalMode、routeCandidateK、docFilterCount |
| `kb.dense.raw` | `recall` | `retrieval-engine` | dense route 返回后 | knowledgeBaseId、count、candidates |
| `kb.sparse.raw` | `recall` | `retrieval-engine` | sparse route 返回后 | knowledgeBaseId、count、candidates |
| `kb.dense.filtered` | `recall` | `retrieval-engine` | dense 本地过滤后 | knowledgeBaseId、count、candidates |
| `kb.sparse.trimmed` | `recall` | `retrieval-engine` | sparse 截断后 | knowledgeBaseId、count、candidates |
| `kb.merged.before_rerank` | `recall` | `retrieval-engine` | 单库融合后、rerank 前 | knowledgeBaseId、count、candidates |
| `kb.rerank.request` | `rerank` | `retrieval-engine` | 单库 rerank 前 | knowledgeBaseId、model、topK、inputCount、candidates |
| `kb.rerank.response` | `rerank` | `retrieval-engine` | 单库 rerank 后 | knowledgeBaseId、outputCount、candidates |
| `kb.rerank.skipped` | `rerank` | `retrieval-engine` | 单库 rerank 未启用 | knowledgeBaseId、reason、inputCount |
| `kb.final` | `recall` | `retrieval-engine` | 单库最终候选 | knowledgeBaseId、count、candidates |
| `kb.recall.completed` | `recall` | `retrieval-engine` | 单库闭环结束 | knowledgeBaseId、denseRaw、denseFiltered、sparseRaw、sparseTrimmed、finalCount、reranked、rerankMs、elapsedMs |
| `kb.recall.failed` | `recall` | `retrieval-engine` | 单库闭环异常 | knowledgeBaseId、error |

### 多知识库合并、元数据补齐、全局排序

| eventName | stage | source | 触发点 | payload 要点 |
| --- | --- | --- | --- | --- |
| `recall.completed` | `recall` | `retrieval-engine` | 多库召回完成 | kbCount、candidateCount、elapsedMs |
| `metadata.enrich.completed` | `retrieval` | `retrieval-engine` | 文档元数据补齐后 | documentIdCount、elapsedMs |
| `global.merge.before_rerank` | `rerank` | `retrieval-engine` | 全局 rerank 前 | rankingPolicy、globalRerankEnabled、topK、inputCount、candidates |
| `global.rerank.request` | `rerank` | `retrieval-engine` | 全局 rerank 前 | model、topK、scoreThreshold、inputCount、candidates |
| `global.rerank.response` | `rerank` | `retrieval-engine` | 全局 rerank 后 | outputCount、chunks |
| `global.rerank.skipped` | `rerank` | `retrieval-engine` | 全局 rerank 跳过 | reason、inputCount |
| `global.final` | `retrieval` | `retrieval-engine` | 检索最终结果 | rankingMode、count、chunks |
| `retrieval.execution.completed` | `retrieval` | `retrieval-engine` | `RetrievalExecutionService.execute` 结束 | totalMs、resultCount、candidateCount、rankingMode、kbTraces |

### 答案生成

| eventName | stage | source | 触发点 | payload 要点 |
| --- | --- | --- | --- | --- |
| `answer_generation.started` | `answer_generation` | `app` | 调用 LLM 生成前 | modelId、messageCount、referenceCount、stream |
| `answer_generation.first_token` | `answer_generation` | `app` | 流式首 token | elapsedMs |
| `answer_generation.completed` | `answer_generation` | `app` | 生成完成 | finishReason、promptTokens、completionTokens、totalTokens、elapsedMs |
| `answer_generation.failed` | `answer_generation` | `app` | 生成异常 | errorCode、message |

## Payload 规范

候选 chunk 统一结构：

```json
{
  "index": 0,
  "kbId": "kb-1",
  "chunkId": "chunk-1",
  "documentId": "doc-1",
  "docId": "external-doc-id",
  "vectorScore": 0.82,
  "sparseScore": 0.31,
  "rankingScore": 0.77,
  "textHash": "sha256",
  "textPreview": "文本预览",
  "metadata": {}
}
```

embedding summary：

```json
{
  "dim": 1024,
  "l2Norm": 12.3,
  "sha256": "sha256",
  "first8": [0.1, 0.2]
}
```

大字段处理：

- query、prompt、answer、chunk content 默认记录 hash 和 preview。
- metadata 可完整记录，但需要避免 auth token、api key 等敏感字段。
- embedding vector 默认不完整记录，除非显式开启。

## 现有代码改造点

### 新增 `rag-trace`

1. 根 `pom.xml` 增加 `<module>rag-trace</module>`。
2. `rag-trace/pom.xml` 依赖 Spring Boot starter、Spring JDBC、Jackson、`rag-core-common`。
3. 实现 trace domain/application/infrastructure。
4. 提供 `TraceAutoConfiguration`，通过 `rag.trace.enabled` 控制启用。

### 改造 retrieval debug writer

保留 `RetrievalDebugTraceWriter` 作为 retrieval 模块内的 payload 组装器，但不再直接拥有 JDBC 逻辑：

```text
RetrievalDebugTraceWriter
  -> 组装 retrieval 专用 payload
  -> TraceRecorder.record(...)
```

文件写入能力下沉到 `rag-trace` 的 `JsonlTraceSink`，通过配置启用。

### 改造 app 编排层

`RagApiService` 中 query rewrite、planning、answer generation 这些 retrieval 外层事件应直接注入 `TraceRecorder` 或通过 `TraceRagProcessEventPublisher` 持久化。

流式场景建议使用组合 publisher：

```text
CompositeRagProcessEventPublisher(SSE publisher, trace publisher)
```

非流式场景虽然当前使用 `RagProcessEventPublisher.NO_OP`，但 trace 仍应记录。可以通过 app 层直接调用 `TraceRecorder`，或创建只包含 trace 的 publisher。

### 改造 Milvus adapter

现有 `milvus.dense.request/response`、`milvus.sparse.request/response` 已经通过 `RetrievalDebugTraceWriter` 记录。保留事件语义，补充 failed 事件。

### 改造 query planner

第一阶段可以由 app 层在 planner 调用前后记录 `planning.request/completed/failed`，避免让 `rag-query-planner` 直接依赖 `rag-trace`。

如果后续 planner 内部需要更细粒度事件，再让 `rag-query-planner` 依赖 `rag-trace` 的 `TraceRecorder` 端口。

## 实施步骤

1. 新建 `rag-trace` 模块、配置类、领域对象、应用服务和 JDBC repository。
2. 增加主业务库 DDL：`t_rag_trace_event`。
3. 实现 `AsyncTraceWriteService`，支持有界队列、batch flush、shutdown drain 和溢出策略。
4. 实现 `JdbcTraceSink` / `JdbcTraceRepository`，确认使用默认 `JdbcTemplate`。
5. 实现 `JsonlTraceSink`，迁移现有文件写入能力。
6. 改造 `RetrievalDebugTraceWriter`，从同步写文件变成调用 `TraceRecorder`。
7. 在 app 层补充 API、planning、query rewrite、answer generation 事件。
8. 补充 Milvus 和单库召回 failed/completed 事件。
9. 添加单元测试和 Spring context 测试。
10. 默认配置保持 trace JDBC 关闭，确认后再在运行环境开启。

## 测试方案

单元测试：

- `DefaultTraceRecorder` 在 disabled 时不入队。
- 队列未满时 `record` 快速返回。
- 队列满时按 `drop_oldest/drop_newest/block` 策略处理。
- `AsyncTraceWriteService` 按 batch size flush。
- `AsyncTraceWriteService` 按 interval flush。
- sink 抛异常时不影响 worker 继续处理后续批次。
- shutdown 时 drain 队列。

JDBC 测试：

- `JdbcTraceRepository.saveBatch` 正确插入 JSONB payload。
- payload 为空时写入 `{}`。
- event_time 使用事件发生时间，而不是数据库当前时间。

Spring context 测试：

- 同时存在主 datasource 和 Dify datasource 时，trace repository 使用主 `JdbcTemplate`。
- `rag.trace.sinks.jdbc.enabled=false` 时不创建 JDBC sink。
- `rag.trace.enabled=false` 时 `TraceRecorder` 使用 no-op 实现。

链路测试：

- query rewrite 开启时，能看到 `query_rewrite.*`、多次 `retrieval.*` 和 `retrieval.rewrite_merge.*`。
- query rewrite 失败时，能看到 `query_rewrite.fallback`，且主请求继续完成。
- hybrid 检索时，能看到 dense/sparse request/response、kb merge、global final。
- 单库 rerank 和全局 rerank 分别能记录 request/response/skipped。
- 流式答案生成能记录 `answer_generation.first_token` 和 completed。

## 风险与取舍

1. 事件量较大：候选列表和 chunk metadata 可能导致 payload 很大。通过 preview、hash、topN 截断和配置控制。
2. 数据库压力：异步批量写可以降低主链路影响，但高 QPS 下仍需评估表增长和索引成本。
3. 事件一致性：默认不阻塞业务，数据库不可用时会丢 trace。这是观测数据的合理取舍。
4. 多 query 改写：一次 HTTP traceId 下会有多次 retrieval requestId，需要同时按 traceId 和 requestId 查询。
5. 敏感信息：必须默认脱敏 auth token、api key、完整 prompt、完整 answer 和 embedding vector。

## 推荐结论

采用独立 `rag-trace` 模块，并以轻量 DDD 分层实现：

- domain 定义通用 trace event 和 repository/sink 端口。
- application 负责异步队列、批量刷写和生命周期。
- infrastructure 负责 JDBC 和 JSONL sink。
- app/retrieval/query-planner 只发布事件，不关心写库细节。
- JDBC sink 明确绑定 `spring.datasource` 主业务库，与 `SPRING_AI_CHAT_MEMORY` 同库，严格禁止写入 Dify 元数据库。

这个方案能覆盖完整 RAG 检索链路，同时保持 retrieval 核心逻辑和 trace 持久化解耦。
