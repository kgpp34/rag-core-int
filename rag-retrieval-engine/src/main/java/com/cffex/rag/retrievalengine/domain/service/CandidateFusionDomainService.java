package com.cffex.rag.retrievalengine.domain.service;

import java.util.List;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.CandidateSet;
import com.cffex.rag.retrievalengine.domain.model.KnowledgeBaseRecallPolicy;

/**
 * 候选融合领域服务。
 */
public class CandidateFusionDomainService {

    public List<RetrievalCandidate> filterDenseCandidates(
            KnowledgeBaseRecallPolicy policy,
            List<RetrievalCandidate> candidates
    ) {
        CandidateSet candidateSet = CandidateSet.fromLegacy(candidates);
        if (policy.scoreThresholdEnabled()) {
            candidateSet = candidateSet.filterByFinalScoreThreshold(policy.scoreThreshold());
        }
        return candidateSet
                .sortByFinalScoreDesc()
                .limit(policy.resolvedTopK())
                .toLegacyCandidates();
    }

    public List<RetrievalCandidate> trimSparseCandidates(
            KnowledgeBaseRecallPolicy policy,
            List<RetrievalCandidate> candidates
    ) {
        CandidateSet candidateSet = CandidateSet.fromLegacy(candidates);
        if (policy.scoreThresholdEnabled()) {
            candidateSet = candidateSet.filterBySparseScoreThreshold(policy.scoreThreshold());
        }
        return candidateSet
                .sortBySparseScoreDesc()
                .limit(policy.resolvedTopK())
                .toLegacyCandidates();
    }

    public List<RetrievalCandidate> fuse(
            KnowledgeBaseRecallPolicy policy,
            List<RetrievalCandidate> denseCandidates,
            List<RetrievalCandidate> sparseCandidates
    ) {
        if (denseCandidates.isEmpty() && sparseCandidates.isEmpty()) {
            return List.of();
        }
        return CandidateSet.fromLegacy(denseCandidates)
                .fuse(
                        CandidateSet.fromLegacy(sparseCandidates),
                        policy.resolvedVectorWeight(),
                        policy.resolvedKeywordWeight()
                )
                .toLegacyCandidates();
    }

    public List<RetrievalCandidate> mergeForRerank(
            List<RetrievalCandidate> denseCandidates,
            List<RetrievalCandidate> sparseCandidates
    ) {
        if (denseCandidates.isEmpty() && sparseCandidates.isEmpty()) {
            return List.of();
        }
        // rerank 分支只负责把多 route 候选合并成一个去重集合，
        // 不在这里做分数归一化，避免和 rerank 的职责重叠。
        return CandidateSet.fromLegacy(denseCandidates)
                .mergeByDocId(CandidateSet.fromLegacy(sparseCandidates))
                .toLegacyCandidates();
    }
}
