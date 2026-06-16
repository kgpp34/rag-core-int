package com.cffex.rag.retrievalengine.domain.service;

import java.util.List;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.CandidateSet;
import com.cffex.rag.retrievalengine.domain.model.KnowledgeBaseRecallPolicy;

/**
 * 单知识库排序领域服务。
 */
public class KnowledgeBaseRankingDomainService {

    public List<RetrievalCandidate> postProcess(
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
}
