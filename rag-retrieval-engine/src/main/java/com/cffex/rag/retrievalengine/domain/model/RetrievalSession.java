package com.cffex.rag.retrievalengine.domain.model;

import java.util.List;
import java.util.Objects;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

/**
 * 一次检索执行会话的最小聚合根。
 */
public record RetrievalSession(
        String rawQueryText,
        List<KnowledgeBaseRecallPolicy> recallPolicies,
        GlobalRankingPolicy globalRankingPolicy,
        String processedQueryText,
        List<RetrievalCandidate> recalledCandidates,
        List<RetrievalCandidate> enrichedCandidates
) {

    public RetrievalSession {
        Objects.requireNonNull(rawQueryText, "rawQueryText must not be null");
        recallPolicies = List.copyOf(Objects.requireNonNull(recallPolicies, "recallPolicies must not be null"));
        Objects.requireNonNull(globalRankingPolicy, "globalRankingPolicy must not be null");
        recalledCandidates = List.copyOf(Objects.requireNonNull(recalledCandidates, "recalledCandidates must not be null"));
        enrichedCandidates = List.copyOf(Objects.requireNonNull(enrichedCandidates, "enrichedCandidates must not be null"));
    }

    public static RetrievalSession start(
            String rawQueryText,
            List<KnowledgeBaseRecallPolicy> recallPolicies,
            GlobalRankingPolicy globalRankingPolicy
    ) {
        return new RetrievalSession(
                rawQueryText,
                recallPolicies,
                globalRankingPolicy,
                null,
                List.of(),
                List.of()
        );
    }

    public RetrievalSession withProcessedQueryText(String processedQueryText) {
        return new RetrievalSession(
                rawQueryText,
                recallPolicies,
                globalRankingPolicy,
                Objects.requireNonNull(processedQueryText, "processedQueryText must not be null"),
                recalledCandidates,
                enrichedCandidates
        );
    }

    public RetrievalSession withRecalledCandidates(List<RetrievalCandidate> candidates) {
        return new RetrievalSession(
                rawQueryText,
                recallPolicies,
                globalRankingPolicy,
                processedQueryText,
                candidates,
                enrichedCandidates
        );
    }

    public RetrievalSession withEnrichedCandidates(List<RetrievalCandidate> candidates) {
        return new RetrievalSession(
                rawQueryText,
                recallPolicies,
                globalRankingPolicy,
                processedQueryText,
                recalledCandidates,
                candidates
        );
    }

    public String effectiveQueryText() {
        return processedQueryText == null ? rawQueryText : processedQueryText;
    }

    public long knowledgeBaseRerankCount() {
        return recallPolicies.stream()
                .filter(KnowledgeBaseRecallPolicy::hasRerankPolicy)
                .count();
    }

    public boolean globalRerankEnabled() {
        return globalRankingPolicy instanceof GlobalRerankPolicy;
    }
}
