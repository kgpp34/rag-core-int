package com.cffex.rag.retrievalengine.domain.model;

/**
 * 候选块在检索执行过程中经历的阶段分值。
 */
public record CandidateScores(
        Double denseScore,
        Double sparseScore,
        Double fusedScore,
        Double rerankScore,
        double finalScore
) {

    public double denseScoreOrZero() {
        return denseScore == null ? 0.0d : denseScore;
    }

    public double sparseScoreOrZero() {
        return sparseScore == null ? 0.0d : sparseScore;
    }

    public CandidateScores afterFusion(double normalizedDenseScore, Double rawSparseScore, double fusedScoreValue) {
        return new CandidateScores(
                normalizedDenseScore,
                rawSparseScore,
                fusedScoreValue,
                rerankScore,
                fusedScoreValue
        );
    }

    public CandidateScores afterRerank(double rerankScoreValue) {
        return new CandidateScores(
                denseScore,
                sparseScore,
                fusedScore,
                rerankScoreValue,
                rerankScoreValue
        );
    }

    public CandidateScores withSparseScore(Double sparseScoreValue) {
        return new CandidateScores(
                denseScore,
                sparseScoreValue,
                fusedScore,
                rerankScore,
                finalScore
        );
    }

    public CandidateScores withFinalScore(double finalScoreValue) {
        return new CandidateScores(
                denseScore,
                sparseScore,
                fusedScore,
                rerankScore,
                finalScoreValue
        );
    }
}
