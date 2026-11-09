# 术语替换

术语替换用于在检索前把用户口语、别名或缩写标准化为专业术语。它同时支持全局规则和单知识库规则。

## 数据库

规则表位于 `SPRING_DATASOURCE_URL` 指向的主业务库，不在 Dify 元数据库中：

```sql
INSERT INTO t_kb_terminology(scope, kb_id, term, "desc")
VALUES
  ('global', NULL, '国债期货', '["中金所国债"]'::jsonb),
  ('single_kb', 'kb-1', '保证金监控', '["保证金系数"]'::jsonb);
```

- `scope=global`：对所有参与检索的知识库生效，`kb_id` 留空。
- `scope=single_kb`：只对指定 `kb_id` 生效。
- `desc`：口语或别名组成的 JSON 字符串数组。

默认在功能启用时自动创建表和索引。数据库 DDL 由 DBA 管理时，将
`RAG_TERMINOLOGY_REPLACEMENT_INITIALIZE_SCHEMA` 设为 `false`，并预先执行
`rag-retrieval-engine` 中的建表 SQL。

## 配置

```dotenv
RAG_TERMINOLOGY_REPLACEMENT_ENABLED=true
RAG_TERMINOLOGY_REPLACEMENT_INITIALIZE_SCHEMA=true
RAG_TERMINOLOGY_REPLACEMENT_INITIAL_DELAY_MS=300000
RAG_TERMINOLOGY_REPLACEMENT_REFRESH_INTERVAL_MS=300000
```

应用启动时立即加载一次，之后按固定延迟全量刷新。单次刷新失败会保留上一版可用快照，错误可通过状态接口查询。

## 运行状态

```http
GET /api/v1/terminology/status
POST /api/v1/terminology/refresh
```

状态包含启用状态、是否成功加载、快照版本、规则数量、最近成功时间和最近错误。排障时不能只检查 `enabled=true`，还应确认 `loaded=true` 且 `lastError` 为空。

## 查询链路

1. 全局规则先应用到检索问题。
2. 每个知识库在全局结果之上应用自己的 `single_kb` 规则。
3. 不同标准化文本拆成独立检索分组并行执行。
4. 多分组结果去重；配置 rerank 时再以用户原问题进行一次全局 rerank。
5. 回答模型同时收到用户原问题、术语映射和实际检索问题变体。原问题不会被直接覆盖。

`/api/v1/retrieval/search` 的 `debugTrace` 在命中规则时包含 `terminology_applied` 和 `terminology_queries`。
