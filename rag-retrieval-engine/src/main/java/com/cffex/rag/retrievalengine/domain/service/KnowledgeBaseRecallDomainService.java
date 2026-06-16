package com.cffex.rag.retrievalengine.domain.service;

import java.util.List;
import java.util.Objects;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.KnowledgeBaseRecallPolicy;

/**
 * 单知识库召回闭环领域服务。
 */
public class KnowledgeBaseRecallDomainService {

    @FunctionalInterface
    public interface KnowledgeBaseRerankExecutor {
        List<RetrievalCandidate> rerank(List<RetrievalCandidate> candidates);
    }

    public record RouteCandidates(
            List<RetrievalCandidate> denseCandidates,
            List<RetrievalCandidate> sparseCandidates
    ) {
        public RouteCandidates {
            denseCandidates = List.copyOf(Objects.requireNonNull(denseCandidates, "denseCandidates must not be null"));
            sparseCandidates = List.copyOf(Objects.requireNonNull(sparseCandidates, "sparseCandidates must not be null"));
        }
    }

    private final CandidateFusionDomainService candidateFusionDomainService;

    public KnowledgeBaseRecallDomainService(CandidateFusionDomainService candidateFusionDomainService) {
        this.candidateFusionDomainService = Objects.requireNonNull(candidateFusionDomainService);
    }

    public List<RetrievalCandidate> recall(
            KnowledgeBaseRecallPolicy policy,
            RouteCandidates routeCandidates,
            KnowledgeBaseRerankExecutor rerankExecutor
    ) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(routeCandidates, "routeCandidates must not be null");
        Objects.requireNonNull(rerankExecutor, "rerankExecutor must not be null");

        if (policy.hasRerankPolicy()) {
            List<RetrievalCandidate> rerankInput = mergeCandidatesForRerank(
                    routeCandidates.denseCandidates(),
                    routeCandidates.sparseCandidates()
            );
            if (rerankInput.isEmpty()) {
                return List.of();
            }
            return rerankExecutor.rerank(rerankInput);
        }

        List<RetrievalCandidate> denseFiltered = filterDenseCandidates(policy, routeCandidates.denseCandidates());
        List<RetrievalCandidate> sparseTrimmed = trimSparseCandidates(policy, routeCandidates.sparseCandidates());
        List<RetrievalCandidate> mergedCandidates = candidateFusionDomainService.fuse(policy, denseFiltered, sparseTrimmed);
        if (mergedCandidates.isEmpty()) {
            return List.of();
        }
        return mergedCandidates;
    }

    public List<RetrievalCandidate> filterDenseCandidates(
            KnowledgeBaseRecallPolicy policy,
            List<RetrievalCandidate> candidates
    ) {
        return candidateFusionDomainService.filterDenseCandidates(policy, candidates);
    }

    public List<RetrievalCandidate> trimSparseCandidates(
            KnowledgeBaseRecallPolicy policy,
            List<RetrievalCandidate> candidates
    ) {
        return candidateFusionDomainService.trimSparseCandidates(policy, candidates);
    }

    public List<RetrievalCandidate> mergeCandidatesBeforeRerank(
            KnowledgeBaseRecallPolicy policy,
            List<RetrievalCandidate> denseFiltered,
            List<RetrievalCandidate> sparseTrimmed
    ) {
        return policy.hasRerankPolicy()
                ? mergeCandidatesForRerank(denseFiltered, sparseTrimmed)
                : candidateFusionDomainService.fuse(policy, denseFiltered, sparseTrimmed);
    }

    public List<RetrievalCandidate> mergeCandidatesForRerank(
            List<RetrievalCandidate> denseCandidates,
            List<RetrievalCandidate> sparseCandidates
    ) {
        return candidateFusionDomainService.mergeForRerank(denseCandidates, sparseCandidates);
    }
}
