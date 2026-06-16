package com.cffex.rag.retrievalengine.application;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.retrievalengine.application.command.WeightedGlobalRankingCommand;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.CandidateSet;

/**
 * weighted 排序服务：消费上游已完成单库融合的最终分数，无外部模型调用。
 *
 * <p>阶段 3 后，HYBRID 库的 dense / sparse 分数已在知识库闭环检索任务内部按知识库权重
 * 融合，最终分数统一承载在 {@code vectorScore} 上；因此本组件只负责按
 * 最终分排序并截断 {@code topK}。
 *
 * <p>阶段 E 起，本组件定位为请求级排序的兼容兜底模式: 仅当规划阶段未启用全局 rerank
 * 时才会被调度，不再承担新的主路径语义。
 */
@Component
public class WeightedRankingService {

    public List<RetrievedChunk> rank(String query,
                                     List<RetrievalCandidate> candidates,
                                     WeightedGlobalRankingCommand spec,
                                     int topK) {
        // 这里已经不再区分 dense/sparse 的原始来源，
        // 默认把 vectorScore 视为“当前阶段最终排序分”直接做请求级截断。
        return CandidateSet.fromLegacy(candidates)
                .sortByFinalScoreDesc()
                .limit(topK)
                .toLegacyCandidates()
                .stream()
                .map(c -> new RetrievedChunk(
                        c.chunkId(),
                        c.documentId(),
                        c.knowledgeBaseId(),
                        c.finalScore(),
                        c.sparseScore(),
                        c.finalScore(),
                        c.content(),
                        c.metadata()
                ))
                .toList();
    }
}
