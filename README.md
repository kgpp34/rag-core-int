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
- `POST /api/v1/rag/answer` 的处理逻辑是：先召回，再把召回片段交给内置 LLM 整理答案
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

## 文档

- `docs/architecture.md`
- `docs/task-plan.md`
