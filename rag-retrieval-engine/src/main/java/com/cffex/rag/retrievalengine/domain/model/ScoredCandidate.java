package com.cffex.rag.retrievalengine.domain.model;

import java.util.Map;
import java.util.Objects;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

/**
 * 候选块及其阶段分值的领域组合对象。
 */
public record ScoredCandidate(Candidate candidate, CandidateScores scores) {

    public ScoredCandidate {
        candidate = Objects.requireNonNull(candidate, "candidate must not be null");
        scores = Objects.requireNonNull(scores, "scores must not be null");
    }

    public static ScoredCandidate fromLegacy(RetrievalCandidate candidate) {
        return new ScoredCandidate(
                new Candidate(
                        candidate.chunkId(),
                        candidate.documentId(),
                        candidate.knowledgeBaseId(),
                        candidate.content(),
                        candidate.metadata()
                ),
                new CandidateScores(
                        candidate.vectorScore(),
                        candidate.sparseScore(),
                        null,
                        null,
                        candidate.vectorScore()
                )
        );
    }

    public RetrievalCandidate toLegacyCandidate() {
        return new RetrievalCandidate(
                chunkId(),
                documentId(),
                knowledgeBaseId(),
                content(),
                finalScore(),
                scores.sparseScore(),
                metadata()
        );
    }

    public String chunkId() {
        return candidate.chunkId();
    }

    public String documentId() {
        return candidate.documentId();
    }

    public String knowledgeBaseId() {
        return candidate.knowledgeBaseId();
    }

    public String content() {
        return candidate.content();
    }

    public Map<String, Object> metadata() {
        return candidate.metadata();
    }

    public String candidateKey() {
        return candidate.candidateKey();
    }

    public String docId() {
        return candidate.docId();
    }

    public double finalScore() {
        return scores.finalScore();
    }

    public Double sparseScore() {
        return scores.sparseScore();
    }

    public double sparseScoreOrZero() {
        return scores.sparseScoreOrZero();
    }

    public ScoredCandidate withMetadata(Map<String, Object> metadata) {
        return new ScoredCandidate(candidate.withMetadata(metadata), scores);
    }

    public ScoredCandidate withScores(CandidateScores newScores) {
        return new ScoredCandidate(candidate, newScores);
    }

    public ScoredCandidate reranked(double rerankScore) {
        return withScores(scores.afterRerank(rerankScore));
    }
}
