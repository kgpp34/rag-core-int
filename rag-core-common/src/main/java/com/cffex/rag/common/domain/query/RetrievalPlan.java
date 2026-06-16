package com.cffex.rag.common.domain.query;

import java.util.List;
import java.util.Objects;

import com.cffex.rag.common.domain.retrieval.EmbeddingSpec;
import com.cffex.rag.common.domain.retrieval.KnowledgeBaseRecallSpec;
import com.cffex.rag.common.domain.retrieval.RankingSpec;

/**
 * 检索执行计划：query-planner 的输出，retrieval-engine 的直接输入。
 *
 * <p>所有运行时决策（使用哪个模型、哪些集合、哪种召回模式、哪种排序策略）已在 planning 阶段完成，
 * retrieval-engine 只负责执行，不再查询元数据服务。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code embeddingSpec} — 已解析的 embedding 模型连接信息</li>
 *   <li>{@code recallSpecs} — 每个目标知识库的独立召回规格</li>
 *   <li>{@code globalRankingSpec} — 请求级全局排序策略与参数</li>
 *   <li>{@code topK} — 最终返回给调用方的结果数量上限</li>
 *   <li>{@code globalScoreThresholdEnabled} — 是否在请求级全局 rerank 后执行最终阈值过滤</li>
 *   <li>{@code globalScoreThreshold} — 请求级全局 rerank 后使用的最终阈值</li>
 * </ul>
 */
public record RetrievalPlan(
        String query,
        EmbeddingSpec embeddingSpec,
        List<KnowledgeBaseRecallSpec> recallSpecs,
        RankingSpec globalRankingSpec,
        int topK,
        boolean globalScoreThresholdEnabled,
        double globalScoreThreshold
) {
    public RetrievalPlan {
        query = Objects.requireNonNull(query, "query must not be null");
        embeddingSpec = Objects.requireNonNull(embeddingSpec, "embeddingSpec must not be null");
        recallSpecs = List.copyOf(Objects.requireNonNull(recallSpecs, "recallSpecs must not be null"));
        globalRankingSpec = Objects.requireNonNull(globalRankingSpec, "globalRankingSpec must not be null");
        if (topK <= 0) throw new IllegalArgumentException("topK must be positive");
    }

    public RetrievalPlan(
            String query,
            EmbeddingSpec embeddingSpec,
            List<KnowledgeBaseRecallSpec> recallSpecs,
            RankingSpec globalRankingSpec,
            int topK
    ) {
        this(query, embeddingSpec, recallSpecs, globalRankingSpec, topK, false, 0.0d);
    }

    /**
     * 兼容旧执行链路：阶段 A 之后，调用方仍可通过此方法读取请求级排序策略。
     */
    public RankingSpec rankingSpec() {
        return globalRankingSpec;
    }
}
