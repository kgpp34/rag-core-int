# rag-core-int

高性能 Java RAG 核心检索引擎。

## 模块

- `rag-core-common`: 公共模型、接口与基础工具
- `rag-metadata-cacher`: Dify 元数据缓存与查询
- `rag-query-planner`: 检索请求规划与上下文构建
- `rag-retrieval-engine`: 检索执行、向量召回、重排编排
- `app`: 应用启动与对外装配

## 环境要求

- JDK 25
- Maven 3.9+

## 快速开始

```bash
mvn clean test
```

启动应用：

```bash
mvn -pl app spring-boot:run
```

## REST API

- `POST /api/v1/retrieval/search`
- `POST /api/v1/rag/answer`
- `POST /api/v1/rag/answer` with `Accept: text/event-stream`

说明：

- 对外只开放两类用户能力：普通召回、增强召回问答
- `POST /api/v1/rag/answer` 先通过 LLM 识别问题意图：纯身份、能力、知识库范围或模式咨询直接返回介绍；其他问题继续普通召回问答或 Agentic 深度思考流程
- 增强召回答案接口不再暴露 LLM 控制项，模型选择、提示词和生成参数由服务内部管理
- `POST /api/v1/rag/answer` 在 `Accept: application/json` 时返回整理后的完整结果
- `POST /api/v1/rag/answer` 在 `Accept: text/event-stream` 时返回 `rag_progress`、`delta`、`done`、`error` SSE 事件

普通召回示例：

```bash
curl -X POST http://localhost:8080/api/v1/retrieval/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "测试问题",
    "docIds": []
  }'
```

增强召回问答流式示例：

```bash
curl -N -X POST http://localhost:8080/api/v1/rag/answer \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "query": "请整理政策要点",
    "userId": "user-001",
    "docIds": [],
    "memory": {
      "conversationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    }
  }'
```

对话记忆仅需传递客户端生成的 `conversationId`。服务端会保存完整历史，并按 token
预算自动注入最近消息；长对话达到阈值后会异步生成摘要，后续请求使用摘要和最近原文组装上下文。

## 能力介绍与意图分流

“你能干什么？”“你有哪些知识库？”“普通模式和深度思考模式有什么区别？”等纯能力咨询，
在普通与深度思考两种模式下均直接返回介绍，不执行查询改写、召回、重排或创建 Agentic Run。
介绍包括助手用途、缓存中的知识库名称、两种模式的适用场景、深度思考耗时提醒，以及选择特定知识库或文档以提高检索相关性的建议。
“你能解释保证金制度吗？”和“你能干什么？顺便解释一下保证金。”等具体或混合问题继续原问答流程。

`docIds` 可以传空列表，表示不限定文档范围；选择文档时，介绍仅列出这些文档对应的知识库。
名称去重排序后最多展示 10 个，超过上限时说明总数。缺少展示名称的内部 ID 不会作为名称展示。
缓存为空或不可用时返回基础介绍。JSON 返回 `answer` 和空 `references`；SSE 返回 `delta`、`done`。
配置会话记忆时保存这次问答，但分类请求本身不读取或写入会话记忆。

配置位于 `app.rag.intent`：

- `enabled` / `RAG_INTENT_ENABLED`：默认 `true`。
- `model` / `RAG_INTENT_MODEL`：可指定轻量分类模型的名称或 ID，默认复用问答模型。
- `timeout` / `RAG_INTENT_TIMEOUT`：默认 `5s`，超时后取消分类并继续原问答流程。
- `max-knowledge-base-names` / `RAG_INTENT_MAX_KNOWLEDGE_BASE_NAMES`：默认 `10`。
- `prompt`：分类系统提示词，要求只返回 `CAPABILITY_INTRO` 或 `KNOWLEDGE_QUERY` 的 JSON。
- `introduction`：介绍文案模板，必须包含 `{knowledgeBases}` 占位符。

默认提示词和完整文案位于 `AppProperties.Intent`。分类使用低温度和小输出预算；调用失败或返回格式不合法时继续原流程。

## 文档

- `docs/architecture.md`
- `docs/task-plan.md`
