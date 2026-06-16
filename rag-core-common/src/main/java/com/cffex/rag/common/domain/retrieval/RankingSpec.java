package com.cffex.rag.common.domain.retrieval;

import java.util.Objects;

/**
 * 排序策略规格：封闭继承体系，穷举所有支持的排序模式。
 *
 * <p>由 query-planner 根据上下文决定使用哪种排序策略并填充参数，retrieval-engine 用
 * switch 表达式进行无歧义派发，无需 {@code supports()} 判断或 {@code @Order} 优先级约定。
 *
 * <p>新增排序模式时：在此处添加新的 permits 子类型，编译器会强制要求所有 switch 站点补充分支。
 */
public sealed interface RankingSpec
        permits RankingSpec.WeightedRankingSpec, RankingSpec.RerankRankingSpec {

    /**
     * 权重融合排序：按 vector/sparse 分数的线性组合排序，无外部模型调用。
     *
     * @param vectorWeight  dense 向量分数权重，通常 ∈ [0, 1]
     * @param keywordWeight 稀疏/全文分数权重，通常 ∈ [0, 1]
     */
    record WeightedRankingSpec(double vectorWeight, double keywordWeight) implements RankingSpec {
        /** 默认权重：仅向量分数，适用于纯语义召回场景。 */
        public static WeightedRankingSpec semanticOnly() {
            return new WeightedRankingSpec(1.0, 0.0);
        }

        /** 等权融合，适用于 HYBRID 模式下未显式配置权重的情况。 */
        public static WeightedRankingSpec equalWeight() {
            return new WeightedRankingSpec(0.5, 0.5);
        }
    }

    /**
     * 模型重排序：调用外部 rerank 模型打分后排序。
     *
     * <p>模型调用端点由 query-planner 从 ModelMeta 解析填充，retrieval-engine 直接使用，
     * 不再通过 modelId 二次查询元数据。
     */
    record RerankRankingSpec(ModelEndpointSpec modelEndpoint) implements RankingSpec {
        public RerankRankingSpec {
            modelEndpoint = Objects.requireNonNull(modelEndpoint, "modelEndpoint must not be null");
        }
    }
}
