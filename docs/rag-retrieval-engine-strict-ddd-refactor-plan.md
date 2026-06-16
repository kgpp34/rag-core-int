# `rag-retrieval-engine` 严格 DDD 重构方案

## 1. 目的

本文给出 `rag-retrieval-engine` 按“严格 DDD”方向继续重构的方案。

这里的“严格 DDD”不是泛泛地指“有 domain 包就算 DDD”，而是指：

- 以领域模型和通用语言作为核心
- 应用层只负责用例编排，不承载大量业务规则
- 基础设施与框架细节完全后置
- 外部共享模型、Spring AI 模型、Milvus SDK 模型都不能直接主导核心领域

本文的目标不是否定当前重构成果，而是说明：

- 当前版本已经具备分层、端口和适配器雏形
- 但如果要进一步追求严格 DDD，仍需要一次方向更明确的架构收敛

## 2. 当前状态判断

## 2.1 当前架构的优点

当前 `rag-retrieval-engine` 已经具备以下良好基础：

- 已按 `application / domain / infrastructure / config` 分层
- 已建立检索与重排的出站端口
- Milvus、Spring AI、HTTP 等外部技术实现已大体放到 `infrastructure`
- 检索工作流、知识库检索任务、模型交互已经从早期大而全服务类中拆出

这说明当前工程已经明显优于“所有逻辑都塞进一个 Service”的传统写法。

## 2.2 当前与严格 DDD 的差距

如果按严格 DDD 要求审视，当前仍存在以下关键问题。

### 2.2.1 应用层承载了过多业务规则

例如当前单知识库闭环处理主要集中在：

- `application.workflow.DefaultModularRagWorkflow`
- `application.retrieval.KnowledgeBaseRetrievalTask`

其中包含：

- dense / sparse 分路
- dense 阈值过滤
- sparse topK 裁剪
- dense/sparse 融合
- 单库 rerank
- 全局排序后的收口处理

这些规则已经不是“简单编排”，而是检索领域的核心业务规则。

在严格 DDD 下，这类规则不应主要留在应用层任务类中，而应沉淀为领域服务、领域策略或领域对象行为。

### 2.2.2 领域模型偏薄

当前核心领域对象 `RetrievalCandidate` 仍然偏向“带少量行为的不可变记录对象”。

它虽然比纯 DTO 更好，但还不足以承载：

- 多阶段分数字义
- 候选集合去重策略
- 候选融合策略
- 分阶段排序与裁剪
- 候选生命周期演进

严格 DDD 下，需要更强语义的领域对象与值对象，而不是大量“List + static method”式处理。

### 2.2.3 Spring AI 类型进入了核心执行层

当前应用层直接使用了：

- `org.springframework.ai.rag.Query`
- `org.springframework.ai.document.Document`
- `org.springframework.ai.chat.client.ChatClient`

这说明：

- Spring AI 已不仅是基础设施技术
- 它已经进入应用主语义

严格 DDD 下，Spring AI 只能作为适配器或反腐层的一部分，不能成为领域和核心用例的主模型。

### 2.2.4 共享内核模型深入模块内部

当前模块较深地依赖 `rag-common` 中的：

- `RetrievalPlan`
- `RankingSpec`
- `KnowledgeBaseRecallSpec`
- `EmbeddingSpec`

这会导致本模块通用语言不完全自治。

严格 DDD 下，应在模块入口建立反腐层，将共享模型转换为本上下文自己的执行命令与领域策略对象。

### 2.2.5 缺少清晰的 bounded context 内部语言

当前代码虽然出现了 `workflow`、`task`、`candidate` 等概念，但模块自己的领域语言还不够稳定。

例如当前没有被清晰定义的核心概念：

- 检索会话
- 单知识库召回策略
- 候选集
- 融合策略
- 排序策略
- 生成上下文

严格 DDD 下，应该围绕这些概念建立稳定的模型，而不是围绕“Spring AI 工作流”建模。

## 3. 严格 DDD 的目标架构

## 3.1 模块重新定位

重构后的 `rag-retrieval-engine` 应定义为：

> 一个以“检索执行领域”为核心的 bounded context。

它只负责：

- 执行上游已经规划好的检索命令
- 在领域内部完成多知识库召回、候选融合、重排、上下文构建与模型交互

它不负责：

- 规划要查哪些知识库
- 生成规划策略
- 决定检索路径选择逻辑

也就是说：

```text
query-planner 负责决策
retrieval-engine 负责执行
```

但执行时使用的领域语言，必须由 `retrieval-engine` 自己定义，而不是由 Spring AI 或共享模型定义。

## 3.2 目标分层

建议按以下分层组织。

### 3.2.1 Domain

领域层只放本上下文自己的通用语言：

- 实体
- 值对象
- 聚合
- 领域服务
- 领域策略
- 领域端口

领域层中不允许出现：

- Spring AI 类型
- Milvus SDK 类型
- HTTP 客户端类型
- Jackson JSON 类型
- `rag-common` 中不属于共享内核最小集合的复杂结构

### 3.2.2 Application

应用层只负责：

- 接收外部命令
- 组装用例上下文
- 调用领域服务
- 协调端口
- 返回结果

应用层不应自己承载复杂融合与排序规则。

### 3.2.3 Infrastructure

基础设施层负责：

- Milvus 检索
- Spring AI ChatClient
- Spring AI Advisor
- Rerank HTTP
- 文档元数据查询
- 配置与装配

这里允许使用任何框架与 SDK，但必须通过端口与应用/领域层连接。

### 3.2.4 Anti-Corruption Layer

建议明确建立反腐层，负责：

- `rag-common` 模型 -> retrieval-engine 领域命令
- Spring AI `Document` -> 领域对象
- 领域上下文 -> Spring AI message / advisor input

反腐层的目的不是复杂化，而是阻止外部模型污染核心领域。

## 4. 严格 DDD 下的领域模型建议

## 4.1 核心聚合：`RetrievalSession`

建议引入聚合根：

- `RetrievalSession`

它代表一次检索执行会话，聚合以下信息：

- 原始查询
- 预处理后的查询
- 每个知识库的召回策略
- 请求级排序策略
- 最终候选集
- 最终上下文块

它不一定要持久化，但应作为“这次检索执行”的一致性边界。

## 4.2 值对象

建议显式建模以下值对象：

- `QueryText`
- `KnowledgeBaseId`
- `DocumentId`
- `ChunkId`
- `CandidateScore`
- `CandidateScores`
- `TopK`
- `ScoreThreshold`
- `RecallMode`

这样可以避免：

- 到处用 `String`
- 到处用 `double`
- 不同阶段分值语义混用

## 4.3 候选模型

建议把当前 `RetrievalCandidate` 拆分为更强语义模型。

可考虑如下结构：

### 4.3.1 `Candidate`

表示候选块的身份与内容：

- `ChunkId`
- `DocumentId`
- `KnowledgeBaseId`
- `Content`
- `CandidateMetadata`

### 4.3.2 `CandidateScores`

表示候选块在各阶段的得分：

- `denseScore`
- `sparseScore`
- `fusedScore`
- `rerankScore`
- `finalScore`

### 4.3.3 `ScoredCandidate`

表示“候选内容 + 分值状态”的组合对象。

通过这种拆分，可以让“分数语义”从隐式字段复用，变成显式领域模型。

## 4.4 候选集合：`CandidateSet`

建议引入：

- `CandidateSet`

它作为不可变集合对象，承载以下行为：

- chunk 去重
- dense/sparse 合并
- 归一化
- 融合
- 裁剪
- 排序

这样很多当前分散在任务类中的静态处理逻辑，都可以转移为集合行为或领域服务。

## 5. 严格 DDD 下的领域服务建议

## 5.1 `KnowledgeBaseRecallDomainService`

职责：

- 执行单知识库召回闭环
- 决定 dense / sparse 路径
- 组合底层检索端口返回值

注意：

- 它是领域服务，不直接依赖 Milvus SDK
- 它通过 `VectorRecallPort` / `FullTextRecallPort` 调用底层实现

## 5.2 `CandidateFusionDomainService`

职责：

- 归一化 dense / sparse 分值
- 按策略融合
- 保留分值语义

当前 `KnowledgeBaseRetrievalTask` 中与融合相关的逻辑应迁移至此。

## 5.3 `KnowledgeBaseRankingDomainService`

职责：

- 单知识库 rerank
- 单知识库阈值过滤
- 单知识库 final topK 裁剪

这样单库排序规则就不再耦合在检索任务编排类中。

## 5.4 `GlobalRankingDomainService`

职责：

- 汇总多知识库候选
- 执行请求级排序
- 统一阈值收口

当前 `DefaultModularRagWorkflow` 中的请求级排序逻辑应迁移至此。

## 5.5 `GenerationContextDomainService`

职责：

- 根据最终候选块构建生成上下文
- 控制上下文长度、顺序、拼装规则

这样“检索结果如何组织给模型”也是领域的一部分，而不只是 ChatClient 技术细节。

## 6. 严格 DDD 下的端口设计

## 6.1 检索端口

建议将当前端口命名继续收敛为更明确的领域语言：

- `VectorRecallPort`
- `FullTextRecallPort`
- `RerankPort`
- `DocumentMetadataPort`
- `ChatCompletionPort`

注意：

- 端口输入输出应尽量使用本领域模型
- 不建议继续用 `Spring AI Document` 作为端口模型

## 6.2 反腐层输入命令

建议新增应用入口命令：

- `ExecuteRetrievalCommand`

它由 `RetrievalPlan` 映射而来，但不直接等同于 `RetrievalPlan`。

内部可进一步拆出：

- `KnowledgeBaseRecallCommand`
- `GlobalRankingCommand`
- `GenerationCommand`

这样即使上游公共模型演进，本模块内部仍能保持稳定。

## 7. Spring AI 在严格 DDD 中的定位

## 7.1 Spring AI 不再主导核心架构

如果坚持严格 DDD，则 Spring AI 的地位必须调整：

- 它可以继续使用
- 但它只能作为基础设施能力提供者

也就是说：

- `Query`
- `Document`
- `Advisor`
- `ChatClient`

这些都不应成为领域模型和核心应用模型。

## 7.2 Spring AI 允许存在的位置

Spring AI 允许存在于：

- `infrastructure.springai.chat`
- `infrastructure.springai.document`
- `infrastructure.springai.advisor`

例如：

- `SpringAiChatCompletionAdapter`
- `SpringAiDocumentMapper`
- `SpringAiAdvisorBridge`

但它们都必须实现领域端口，而不是让应用层直接围绕它们写业务逻辑。

## 8. 推荐包结构

建议重构为如下包结构：

```text
com.cffex.rag.retrievalengine
  application
    command
      ExecuteRetrievalCommand
      GenerateAnswerCommand
    service
      RetrievalApplicationService
      GenerationApplicationService
    acl
      RetrievalPlanMapper
      SpringAiDocumentAclMapper
  domain
    model
      RetrievalSession
      Candidate
      ScoredCandidate
      CandidateSet
      KnowledgeBaseRecallPolicy
      GlobalRankingPolicy
    valueobject
      QueryText
      KnowledgeBaseId
      DocumentId
      ChunkId
      CandidateScores
      TopK
      ScoreThreshold
    service
      KnowledgeBaseRecallDomainService
      CandidateFusionDomainService
      KnowledgeBaseRankingDomainService
      GlobalRankingDomainService
      GenerationContextDomainService
    port
      VectorRecallPort
      FullTextRecallPort
      RerankPort
      DocumentMetadataPort
      ChatCompletionPort
  infrastructure
    vector
      milvus
    fulltext
      milvus
    rerank
      http
    chat
      springai
    metadata
    config
```

## 9. 现有类到目标结构的迁移建议

## 9.1 应保留但降级为应用编排的类

### `DefaultRetrievalEngine`

保留，但应重命名或收敛为：

- `RetrievalApplicationService`

职责变为：

- 接收外部命令
- 调用领域服务
- 返回结果

不再直接依赖复杂工作流对象。

### `DefaultLlmService`

保留为应用服务入口，内部通过 `ChatCompletionPort` 调用基础设施。

## 9.2 应拆解的类

### `DefaultModularRagWorkflow`

建议逐步退出主语义。

原因：

- 它更适合 Spring AI 主导架构
- 不适合作为严格 DDD 的核心对象

处理方式：

- 短期保留为兼容流程壳
- 中期将其内部规则拆到领域服务
- 最终让它只剩一个薄薄的编排层，或彻底删除

### `KnowledgeBaseRetrievalTask`

建议拆成：

- 应用层任务编排
- 领域服务
- 领域策略

至少要把以下逻辑移出：

- dense 过滤
- sparse 裁剪
- 归一化
- 融合
- 单库 rerank 后裁剪

### `RetrievalCandidate`

建议拆分为：

- `Candidate`
- `CandidateScores`
- `ScoredCandidate`
- `CandidateSet`

## 9.3 应留在基础设施层的类

### `MilvusVectorSearchAdapter`

保留，但实现的应是：

- `VectorRecallPort`

而不是与应用层流程类耦合过深。

### `MilvusFullTextSearchAdapter`

保留，但应只负责：

- 发起 Milvus sparse 检索
- 返回领域候选对象

不参与更上层融合规则。

### `SpringAiChatInteractionService`

建议改造成：

- `SpringAiChatCompletionAdapter`

明确它是 `ChatCompletionPort` 的基础设施实现，而不是“默认模型交互应用服务”。

## 10. 分阶段重构路径

## 10.1 第一阶段：边界收缩

目标：

- 阻断 Spring AI 类型继续向核心层扩散
- 阻断 `rag-common` 模型继续深度渗透

建议动作：

- 引入 `ExecuteRetrievalCommand`
- 在入口处把 `RetrievalPlan` 映射为本模块内部命令
- 新增 ACL mapper
- 把 Spring AI `Document` / `Query` 收缩到适配层

阶段产出：

- 模块有了自己的内部命令语言
- 外部模型不再深度污染内部结构

## 10.2 第二阶段：领域建模

目标：

- 建立稳定的核心领域对象

建议动作：

- 定义 `RetrievalSession`
- 定义 `Candidate` / `CandidateScores` / `CandidateSet`
- 定义 `KnowledgeBaseRecallPolicy` / `GlobalRankingPolicy`

阶段产出：

- 融合、排序、裁剪开始围绕领域对象表达

## 10.3 第三阶段：规则下沉

目标：

- 把当前流程类里的业务规则迁入领域层

建议动作：

- 抽出 `KnowledgeBaseRecallDomainService`
- 抽出 `CandidateFusionDomainService`
- 抽出 `KnowledgeBaseRankingDomainService`
- 抽出 `GlobalRankingDomainService`

阶段产出：

- 应用层真正瘦身
- 领域层成为规则中心

## 10.4 第四阶段：基础设施回填

目标：

- 用端口把基础设施重新接回领域

建议动作：

- Milvus 适配器实现新的检索端口
- Spring AI ChatClient 适配器实现新的聊天端口
- HTTP rerank 实现新的重排端口

阶段产出：

- 基础设施可以替换
- 核心领域保持稳定

## 10.5 第五阶段：删除过渡结构

目标：

- 清理工作流中心化的过渡方案

建议动作：

- 删除或弱化 `DefaultModularRagWorkflow`
- 删除临时 mapper 与兼容壳
- 补齐测试与文档

阶段产出：

- 严格 DDD 结构收敛完成

## 11. 风险与代价

## 11.1 短期类数量会明显增多

严格 DDD 会引入：

- 更多值对象
- 更多领域服务
- 更多反腐层对象

这会提高短期理解成本，但换来长期边界稳定。

## 11.2 短期开发速度会变慢

因为需要先建立通用语言，再继续开发功能。

## 11.3 Spring AI 集成会变得更“远”

这不是坏事，而是严格 DDD 的必然结果。

Spring AI 不再是核心语义，只是基础设施实现。

## 11.4 初期会存在双模型并存

在迁移过程中，可能会同时存在：

- `RetrievalPlan`
- `ExecuteRetrievalCommand`

以及：

- `RetrievalCandidate`
- `Candidate` / `CandidateSet`

这属于正常过渡，应通过分阶段删除控制时长。

## 12. 最终建议

如果你明确要求 `rag-retrieval-engine` 走严格 DDD，那么推荐的方向不是：

- 在现有 Spring AI 工作流架构上继续做命名优化

而是：

- 收缩技术框架边界
- 建立 retrieval-engine 自己的领域语言
- 让应用层回到编排位置
- 让领域层承载真正的检索规则
- 让基础设施层只负责接入 Milvus、Spring AI、HTTP 与元数据服务

一句话概括：

> 严格 DDD 下的 `rag-retrieval-engine`，不应是“Spring AI 驱动的检索工作流模块”，而应是“以检索执行领域为中心、以 Spring AI/Milvus 为基础设施适配器的领域模块”。

## 13. 与当前方案的关系

当前已经落地的 Spring AI 重构成果并不是无效的。

它仍然可以作为严格 DDD 方案的过渡基础，尤其是：

- 分层已经初步建立
- 检索端口已经存在
- 单库闭环任务已经形成
- 模型交互已经独立出来

但接下来要转换视角：

- 之前是“先把模块 Spring AI 化”
- 之后要变成“把 Spring AI 收回到基础设施层”

也就是说：

```text
阶段一到四的 Spring AI 重构
    -> 作为过渡态
    -> 再进入严格 DDD 收敛
```

## 14. 推荐下一步

如果按本文继续推进，最合理的下一步不是直接大面积改代码，而是先做一份“第一阶段边界收缩设计稿”，明确：

- 哪些 `rag-common` 模型要在入口被映射
- 哪些 Spring AI 类型必须退出核心层
- 哪些现有类会被替换
- 哪些类保留为过渡壳

建议下一份文档主题为：

- `rag-retrieval-engine-strict-ddd-phase1-boundary-plan.md`

然后再进入代码改造。
