package com.cffex.rag.common.service;

import com.cffex.rag.common.domain.query.RetrievalPlan;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.common.domain.retrieval.RetrievalResult;
import com.cffex.rag.common.domain.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 检索引擎出站端口：接收已规划好的 {@link RetrievalPlan}，负责执行但不负责规划。
 *
 * <p>调用方（通常是 query-planner 或 app 层）在调用此接口前，已完成元数据解析、
 * 知识库映射、模型选择等"规划"工作，并将结果打包进 RetrievalPlan。
 * 引擎自身不再依赖 MetadataQueryService。
 */
public interface RetrievalEngine {
    RetrievalResult execute(RetrievalPlan plan);

    default RetrievalResult execute(RetrievalPlan plan, RagProcessEventPublisher eventPublisher) {
        return execute(plan);
    }

    /**
     * 对已检索的 chunk 列表执行 rerank 重排序。
     *
     * <p>适用于多路检索合并后需要统一重排的场景。
     *
     * @param query       原始查询文本，作为 rerank 的参照
     * @param chunks      待重排的 chunk 列表
     * @param rerankModel rerank 模型端点
     * @param topK        保留的最终数量
     * @return 按 rerank 分数降序排列的 chunk 列表
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, ModelEndpointSpec rerankModel, int topK);

    default List<RetrievedChunk> rerank(
            String query,
            List<RetrievedChunk> chunks,
            ModelEndpointSpec rerankModel,
            int topK,
            RagProcessEventPublisher eventPublisher,
            String scope
    ) {
        return rerank(query, chunks, rerankModel, topK);
    }
}
