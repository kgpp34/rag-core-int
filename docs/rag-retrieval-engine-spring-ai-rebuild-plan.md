# `rag-retrieval-engine` Spring AI 全量重构方案

## 1. 背景与目标

当前 `rag-retrieval-engine` 模块已经部分引入 Spring AI，但整体仍然是“自研检索编排 + 局部使用 Spring AI SDK”的混合状态。

现在的重构前提已经非常明确：

- 老板要求该模块整体按 Spring AI 体系重构
- 不能再保留太多历史上的自我设计
- 但新引擎仍然必须保留核心业务能力，而不是退化成一个简单的 `VectorStore + QuestionAnswerAdvisor`

基于此，本方案的目标不是“抵抗 Spring AI 化”，而是设计一套**真正以 Spring AI 为骨架，同时保留业务检索能力和可扩展性的检索引擎架构**。

## 2. 重构约束

新的检索引擎必须满足以下约束：

1. 依旧负责检索与模型交互，不负责查询规划
2. 支持多个知识库的混合检索
3. 基于 Java 25，尽量使用高性能实现和新特性
4. 向量检索和全文检索要流出扩展点，支持不同数据源
5. 要有良好的设计模式，既支持默认工作流，也支持未来自定义工作流

## 3. 总体判断

## 3.1 采用 Spring AI 的方式

建议采用：

- **Spring AI Modular RAG 作为主架构骨架**
- **Spring AI Advisor/ChatClient 作为模型交互入口**
- **自定义 QueryTransformer / DocumentRetriever / DocumentPostProcessor / QueryAugmenter 实现业务能力**

不建议采用：

- 只用 `QuestionAnswerAdvisor + VectorStore` 的 naive RAG 模式
- 让 Spring AI 默认 `VectorStoreDocumentRetriever` 直接接管整个检索执行

原因是当前业务需要的不是“单向量库问答增强”，而是“规划驱动的多知识库混合检索执行引擎”。

## 3.2 Spring AI 在新架构中的角色

Spring AI 在新架构里不再只是某个 SDK，而是成为模块的主结构标准：

- 用 Spring AI 的 Modular RAG 概念拆分检索阶段
- 用 Spring AI 的 `Query`、`Document`、Advisor、ChatClient 统一模型交互语义
- 用 Spring AI 的扩展接口承载默认与自定义工作流

但是具体的多库混检、并发模型、融合、排序、数据源适配，仍需要在这些 Spring AI 扩展点中实现，而不是完全依赖开箱即用默认实现。

## 4. 新模块定位

重构后的 `rag-retrieval-engine` 应重新定义为：

> 一个基于 Spring AI Modular RAG 的检索执行与模型交互引擎。

它的职责包括：

- 接收上游已经规划好的 `RetrievalPlan`
- 执行 query 预处理
- 按知识库执行 dense / sparse / hybrid 检索
- 完成单库后处理与全局排序
- 组织检索结果与 LLM 交互
- 输出标准化的 `RetrievalResult`

它明确**不负责**：

- 决定查哪些库
- 决定使用哪种规划策略
- 动态查询元数据来做规划

也就是：

```text
query-planner 决定“怎么查”
retrieval-engine 负责“把这个方案执行好”
```

## 5. 目标架构

## 5.1 总体分层

建议将新模块按四层组织：

1. Spring AI 工作流层
2. 检索执行编排层
3. 检索适配层
4. 模型交互层

对应结构如下：

```text
DefaultRetrievalEngine
  -> RetrievalWorkflowRegistry
      -> DefaultModularRagWorkflow
          -> Query preprocessing
          -> MultiKnowledgeBaseRetriever
          -> Post retrieval processors
          -> Ranking pipeline
  -> ChatInteractionService

VectorRetrievers / FullTextRetrievers / RerankAdapters / LlmAdapters
  -> concrete data source implementations
```

## 5.2 与 Spring AI Modular RAG 的映射

建议将业务流程映射到 Spring AI 结构时，保持如下对应关系：

- Pre-Retrieval
  - query rewrite
  - query compression
  - query expansion
  - query normalization
- Retrieval
  - 多知识库路由后的 dense / sparse / hybrid 检索
  - 不同数据源的检索适配器
- Post-Retrieval
  - 单库 topK / threshold
  - dense/sparse 融合
  - 单库 rerank
  - 全局排序前的候选整理
- Generation
  - 上下文注入
  - ChatClient / Advisor
  - LLM 调用

换句话说，Spring AI 的 Modular RAG 不是替代业务逻辑，而是给业务逻辑提供统一的“阶段边界”和“扩展契约”。

## 6. 核心设计方案

## 6.1 入口层：`DefaultRetrievalEngine`

入口服务继续保留 `RetrievalEngine` 接口实现，职责固定为：

1. 接收 `RetrievalPlan`
2. 选择一个 `RetrievalWorkflow`
3. 执行工作流
4. 汇总检索结果与调试信息
5. 如有需要，调用 Spring AI `ChatClient` 完成模型交互

建议它只作为“流程入口”和“工作流派发器”，不要再承担复杂的召回细节。

## 6.2 工作流层：`RetrievalWorkflow`

这是本次重构最重要的抽象。

建议新增：

- `RetrievalWorkflow`
- `DefaultModularRagWorkflow`
- `RetrievalWorkflowRegistry`

其中：

- `RetrievalWorkflow` 是统一工作流接口
- `DefaultModularRagWorkflow` 是默认实现
- `RetrievalWorkflowRegistry` 用于根据配置、场景或策略切换不同工作流

接口建议类似：

```java
public interface RetrievalWorkflow {
    RetrievalWorkflowResult execute(RetrievalExecutionContext context);
}
```

这样可以同时支持：

- 默认工作流
- 未来自定义工作流
- 特定业务域工作流
- 实验性工作流 A/B 切换

这满足“良好的设计模式”和“后续自定义检索工作流”的要求。

## 6.3 Spring AI 查询预处理层

建议把 Spring AI 的 query 相关能力真正引入，而不是只引入 vector store。

建议抽象：

- `QueryPreprocessor`
- `QueryTransformerChain`
- `QueryExpansionPolicy`

默认工作流可按如下方式组织：

1. `CompressionQueryTransformer`
   - 处理多轮对话，把上下文压缩成独立查询
2. `RewriteQueryTransformer`
   - 让 query 更适合检索
3. 自定义 `QueryTransformer`
   - 业务术语标准化、金融缩写展开、别名替换
4. `MultiQueryExpander`
   - 仅在需要提升召回率时启用

要点是：

- query 改写属于 Spring AI 预处理阶段
- 它不负责规划，只负责让“已经要查的东西”更容易被检索到

## 6.4 多知识库检索层：`MultiKnowledgeBaseRetriever`

这是新的检索核心。

建议新增一个 Spring AI 风格的总检索器：

- `MultiKnowledgeBaseDocumentRetriever implements DocumentRetriever`

但注意，它并不是普通的“单数据源文档检索器”，而是一个**基于计划执行多个知识库检索的组合检索器**。

它的输入仍然是 Spring AI 的 `Query`，但会结合 `RetrievalExecutionContext` 中的 `recallSpecs` 执行多库检索。

内部职责：

1. 遍历 `KnowledgeBaseRecallSpec`
2. 为每个知识库创建一个 `KnowledgeBaseRetrievalTask`
3. 在知识库内决定使用 dense / sparse / hybrid
4. 汇总所有库的候选结果

## 6.5 知识库执行单元：`KnowledgeBaseRetrievalTask`

建议把“每个库一个闭环任务”作为核心执行模型。

这是比旧的“全局协调器”式召回编排更适合 Spring AI 重构的方式。

单个知识库的执行过程如下：

```text
KnowledgeBaseRetrievalTask
  -> preprocessQueryForThisKb
  -> parallel dense retrieve / sparse retrieve
  -> single knowledge base post-process
  -> optional rerank
  -> return candidates
```

这样做的好处：

- 更符合 Spring AI Modular RAG 的模块化思想
- 符合“多个库的混合检索”
- 单库结果可以独立做后处理
- 更适合 Java 25 并发建模

## 6.6 检索扩展点：向量 / 全文双端口

你的第 4 点要求必须保留，这里不能退回到只认 `VectorStore`。

建议保留两类独立扩展点：

- `VectorDocumentRetriever`
- `FullTextDocumentRetriever`

接口建议如下：

```java
public interface VectorDocumentRetriever {
    String sourceType();
    List<Document> retrieve(VectorRetrievalRequest request);
}
```

```java
public interface FullTextDocumentRetriever {
    String sourceType();
    List<Document> retrieve(FullTextRetrievalRequest request);
}
```

说明：

- `sourceType()` 用于标识具体数据源能力，如 `milvus`、`elasticsearch`、`opensearch`
- `VectorDocumentRetriever` 负责 dense
- `FullTextDocumentRetriever` 负责 sparse/full-text
- 混合检索由上层工作流组合，不由单个端口自己“暗中完成”

这样既满足 Spring AI 化，也保留了不同数据源的扩展能力。

## 6.7 默认适配器实现

默认实现建议为：

- `MilvusVectorDocumentRetriever`
- `MilvusFullTextDocumentRetriever`
- `SpringAiChatInteractionService`
- `HttpRerankService`

命名统一原则：

- 领域/应用层按能力命名
- 基础设施层按外部系统命名
- 不再使用 `SpringAiVectorSearchPort` 这种把框架名带进核心能力抽象的命名
- 已收敛为 `MilvusVectorSearchAdapter` 这类按“外部系统 + 能力”命名的实现

## 6.8 排序与后处理层

排序层不应被 Advisor 吞掉，而应在检索工作流内部明确建模。

建议拆分为：

- `KnowledgeBasePostProcessor`
- `CandidateFusionService`
- `KnowledgeBaseRerankService`
- `GlobalRankingService`

默认流程：

1. dense threshold / topK
2. sparse topK
3. 单库融合
4. 单库 rerank
5. 多库汇总
6. 全局 weighted 或全局 rerank
7. 全局 threshold / topK

这样保留了现有业务能力，同时让每一层职责清晰。

## 6.9 模型交互层

由于你要求新引擎继续负责模型交互，这一层需要保留并升级。

建议新增：

- `ChatInteractionService`
- `SpringAiChatInteractionService`

职责：

- 基于 `ChatClient` 与 LLM 交互
- 负责注入 Advisor
- 负责把检索结果拼成生成上下文
- 支持同步和流式生成

在这里可以真正引入：

- `RetrievalAugmentationAdvisor`
- 自定义 Advisor
- 观测埋点和链路透传

但注意：

- Advisor 负责 generation augmentation
- 检索执行核心仍在 `RetrievalWorkflow`

## 7. Java 25 设计建议

## 7.1 并发模型

建议优先采用：

- 虚拟线程
- 结构化并发

重构目标不是继续使用简单的“平铺 `invokeAll()`”，而是把并发结构建模为：

```text
请求级任务
  -> 知识库级任务
      -> dense 子任务
      -> sparse 子任务
```

建议封装一层：

- `RetrievalTaskExecutor`

底层优先使用：

- `StructuredTaskScope`
- `Executors.newVirtualThreadPerTaskExecutor()`

适用点：

- 多知识库并发
- 单库内 dense/sparse 并发
- query 扩展后的多 query 检索并发

## 7.2 数据模型

Java 25 下建议更彻底使用：

- `record`
- `sealed interface`
- pattern matching

例如：

- `RetrievalWorkflowResult` 用 `record`
- `RetrievalRequest` 分为 `VectorRetrievalRequest` / `FullTextRetrievalRequest`
- `RetrievalRoute` 可用 `enum`
- 工作流上下文可用不可变对象建模

原则是：

- 配置对象与上下文对象不可变
- 检索结果尽量不可变
- 通过构造新对象而不是原地修改来控制阶段语义

## 7.3 可观测性

Java 25 并发增强之后，更要补 observability：

- 每个知识库任务单独打点
- dense/sparse 分路耗时
- rerank 耗时
- query rewrite / expansion 耗时
- LLM 调用耗时

建议让 Advisor 层、工作流层、数据源适配层都带统一 trace 语义。

## 8. 推荐设计模式

## 8.1 Strategy

用于：

- `RetrievalWorkflow`
- `GlobalRankingService`
- `QueryPreprocessor`

好处是默认实现和自定义实现可以平滑共存。

## 8.2 Template Method

用于：

- 默认工作流骨架

例如 `AbstractRetrievalWorkflow`：

```text
prepareQuery
-> retrieveDocuments
-> postProcess
-> rank
-> buildResult
```

不同工作流只需要改写特定步骤。

## 8.3 Factory / Registry

用于：

- 按 `sourceType` 获取 vector/full-text retriever
- 按 workflow 名称或场景获取工作流实现

建议保留：

- `RetrieverRegistry`
- `WorkflowRegistry`

## 8.4 Chain of Responsibility

用于：

- query transformer chain
- document post processor chain
- advisor chain

这和 Spring AI 本身的阶段设计天然一致。

## 8.5 Composite

用于：

- 多知识库组合检索器
- 多 query 扩展后的组合检索

`MultiKnowledgeBaseDocumentRetriever` 本质上就是一个组合检索器。

## 9. 推荐包结构

建议重构后的包结构如下：

```text
com.cffex.rag.retrievalengine
  application
    DefaultRetrievalEngine
    workflow
      RetrievalWorkflow
      AbstractRetrievalWorkflow
      DefaultModularRagWorkflow
      RetrievalWorkflowRegistry
      RetrievalExecutionContext
      RetrievalWorkflowResult
    query
      QueryPreprocessor
      DefaultQueryPreprocessor
      transformer
      expander
    retrieval
      MultiKnowledgeBaseDocumentRetriever
      KnowledgeBaseRetrievalTask
      RetrieverRegistry
      port
        VectorDocumentRetriever
        FullTextDocumentRetriever
    postprocess
      KnowledgeBasePostProcessor
      CandidateFusionService
      KnowledgeBaseRerankService
      GlobalRankingService
    chat
      ChatInteractionService
      SpringAiChatInteractionService
  domain
    request
    result
    candidate
    route
  infrastructure
    vector
      milvus
    fulltext
      milvus
      elasticsearch
    rerank
    llm
    advisor
    execution
  config
```

## 10. 默认工作流设计

建议默认工作流为：

```text
RetrievalPlan
  -> build RetrievalExecutionContext
  -> query preprocess
  -> multi-knowledge-base retrieval
  -> knowledge-base post process
  -> global ranking
  -> enrich metadata
  -> build RetrievalResult
```

如果同时需要模型交互，则在检索结果产出后继续：

```text
RetrievalResult
  -> QueryAugmenter / Advisor
  -> ChatClient
  -> LLM response
```

这样就能同时满足：

- 检索引擎负责检索
- 检索引擎负责模型交互
- 检索引擎不负责规划

## 11. 自定义工作流设计

为了满足未来扩展，建议预留以下工作流类型：

- `DefaultModularRagWorkflow`
  - 默认标准工作流
- `FastRecallWorkflow`
  - 低成本、低时延场景
- `HighPrecisionWorkflow`
  - 更重的 query rewrite / rerank / post-process
- `DomainCustomizedWorkflow`
  - 特定业务域专用

切换方式建议由：

- 配置
- 计划字段
- 实验开关

三者之一驱动，但决策仍由上游系统给出，检索引擎只执行。

## 12. 与当前实现的主要差异

重构后与当前模块相比，会发生以下关键变化：

### 12.1 保留的东西

- 入口仍然是执行 `RetrievalPlan`
- 检索引擎仍不负责规划
- 仍支持多知识库 hybrid 检索
- 仍支持单库和全局排序
- 仍支持多数据源检索扩展点

### 12.2 替换的东西

- 旧的 `HybridRecallCoordinator` 已退出主链路并被删除
- 原先偏自研的召回流程改为 Spring AI 模块化工作流
- 原先基于单一服务类堆积职责的实现改为工作流 + 检索器 + 后处理器拆分
- 原先“局部 Spring AI”改为“整体 Spring AI 化”

### 12.3 明确放弃的东西

- 继续沿用以 `SpringAiVectorSearchPort` 为代表的旧命名体系
- 继续让检索抽象和具体 SDK 强绑定
- 继续把单库执行、全局汇总、排序逻辑全部堆在同一个协调器中
- 继续保留 `OpenAICompatibleLlmAdapter`、`LlmPort` 这类旧兼容层作为主路径

## 13. 分阶段落地建议

虽然目标是全量 Spring AI 重构，但工程落地仍建议分阶段。

## 13.1 第一阶段：搭骨架

目标：

- 建立新的工作流层
- 建立新的 retriever registry
- 建立新的 chat interaction 层
- 保证旧能力可以迁移进新骨架

产出：

- `RetrievalWorkflow`
- `DefaultModularRagWorkflow`
- `RetrieverRegistry`
- `ChatInteractionService`

## 13.2 第二阶段：迁移检索能力

目标：

- 把 dense / sparse / hybrid 逻辑迁入新的知识库任务模型
- 引入请求级 query preprocess
- 重构多库并发执行模型

产出：

- `MultiKnowledgeBaseDocumentRetriever`
- `KnowledgeBaseRetrievalTask`
- QueryTransformer 链

## 13.3 第三阶段：迁移模型交互

目标：

- 基于 `ChatClient` 和 Advisor 重构模型交互层
- 引入可配置的 `RetrievalAugmentationAdvisor`
- 支持同步和流式模式

产出：

- `SpringAiChatInteractionService`
- 自定义 Advisor 配置

## 13.4 第四阶段：清理历史包袱

目标：

- 删除旧协调器和旧命名
- 收敛数据模型
- 补齐测试、日志和文档

产出：

- 历史类删除
- 包结构稳定
- 新文档和新测试完善

### 13.4 当前进展

当前已完成的第四阶段收敛包括：

- 删除 `HybridRecallCoordinator`
- 删除 `OpenAICompatibleLlmAdapter` 与 `LlmPort`
- 删除 `SpringAiVectorSearchPort`、`SpringAiVectorStoreFactory`、`RagFilterExpressionFactory`
- 将 Milvus dense 适配器收敛为 `MilvusVectorSearchAdapter`

## 14. 风险与注意事项

### 14.1 风险一：Spring AI 化后能力被“默认实现”拉低

规避方式：

- 使用 Spring AI 扩展接口，不直接依赖默认 naive RAG

### 14.2 风险二：Advisor 和 RetrievalWorkflow 职责混淆

规避方式：

- Advisor 只负责模型交互增强
- 检索核心仍放在工作流内部

### 14.3 风险三：重构后扩展点被封死

规避方式：

- 向量检索和全文检索必须保持独立扩展接口
- 不允许把所有检索都收敛成单一 `VectorStore`

### 14.4 风险四：并发模型表面 Spring AI 化，实际性能退化

规避方式：

- 用 Java 25 的虚拟线程和结构化并发重建任务执行器
- 做单库级与全局级指标采集

## 15. 最终建议

如果必须对整个 `rag-retrieval-engine` 模块做 Spring AI 全量重构，那么最合理的方案不是：

- “把原有逻辑删掉，改成 Spring AI 默认 RAG Demo”

而是：

- “以 Spring AI Modular RAG 为主架构”
- “以 Spring AI Advisor/ChatClient 为模型交互标准”
- “以多知识库闭环检索任务为核心执行模型”
- “以双检索端口和工作流注册表保留业务扩展性”
- “以 Java 25 虚拟线程和结构化并发重建高性能执行引擎”

一句话概括：

> 新的 `rag-retrieval-engine` 应该是一个“基于 Spring AI 的模块化检索执行平台”，而不是一个“披着 Spring AI 外壳的简化向量检索器”。

## 16. 参考方向

本方案的设计方向主要参考：

- Spring AI 的 Modular RAG 分阶段模型
- Spring AI 的 Advisor / ChatClient 交互模式
- 当前项目中“检索执行不负责规划”的既有边界

可参考的官方资料：

- [Spring AI Retrieval Augmented Generation](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/retrieval-augmented-generation.html)
- [Spring AI Advisors API](https://docs.spring.io/spring-ai/reference/1.0/api/advisors.html)
