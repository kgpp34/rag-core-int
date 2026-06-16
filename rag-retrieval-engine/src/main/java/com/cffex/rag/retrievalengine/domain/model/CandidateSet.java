package com.cffex.rag.retrievalengine.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

/**
 * 候选集合领域对象，承载排序、裁剪和融合等集合级行为。
 */
public final class CandidateSet {

    private final List<ScoredCandidate> candidates;

    private CandidateSet(List<ScoredCandidate> candidates) {
        this.candidates = List.copyOf(candidates);
    }

    public static CandidateSet empty() {
        return new CandidateSet(List.of());
    }

    public static CandidateSet of(List<ScoredCandidate> candidates) {
        return candidates.isEmpty() ? empty() : new CandidateSet(candidates);
    }

    public static CandidateSet fromLegacy(List<RetrievalCandidate> candidates) {
        return of(candidates.stream().map(ScoredCandidate::fromLegacy).toList());
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    public int size() {
        return candidates.size();
    }

    public List<RetrievalCandidate> toLegacyCandidates() {
        return candidates.stream()
                .map(ScoredCandidate::toLegacyCandidate)
                .toList();
    }

    public CandidateSet filterByFinalScoreThreshold(double threshold) {
        return of(candidates.stream()
                .filter(candidate -> candidate.finalScore() > threshold)
                .toList());
    }

    public CandidateSet filterBySparseScoreThreshold(double threshold) {
        return of(candidates.stream()
                .filter(candidate -> candidate.sparseScoreOrZero() > threshold)
                .toList());
    }

    public CandidateSet sortByFinalScoreDesc() {
        return of(candidates.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::finalScore).reversed())
                .toList());
    }

    public CandidateSet sortBySparseScoreDesc() {
        return of(candidates.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::sparseScoreOrZero).reversed())
                .toList());
    }

    public CandidateSet limit(int topK) {
        return of(candidates.stream()
                .limit(topK)
                .toList());
    }

    public CandidateSet mergeByDocId(CandidateSet other) {
        if (isEmpty() && other.isEmpty()) {
            return empty();
        }

        // hybrid + rerank 不做归一化融合，只做 union + doc_id 去重。
        // 这里要和 Dify 一致：同一个 doc_id 只保留最先出现的候选，不做“挑更高分代表”。
        Map<String, ScoredCandidate> representatives = new LinkedHashMap<>();
        for (ScoredCandidate candidate : candidates) {
            representatives.putIfAbsent(candidate.docId(), candidate);
        }
        for (ScoredCandidate candidate : other.candidates) {
            representatives.putIfAbsent(candidate.docId(), candidate);
        }
        return of(new ArrayList<>(representatives.values()));
    }

    public CandidateSet fuse(CandidateSet sparseCandidates, double vectorWeight, double keywordWeight) {
        if (isEmpty() && sparseCandidates.isEmpty()) {
            return empty();
        }

        // weighted 后处理仍然采用归一化后的 dense/sparse 加权融合，
        // 这里保留原有分值对齐逻辑，避免不同 route 的量纲直接相加。
        Map<String, Double> denseScores = normalizeScoresByChunkId(candidates, ScoredCandidate::finalScore);
        Map<String, Double> sparseScores = normalizeScoresByChunkId(
                sparseCandidates.candidates,
                ScoredCandidate::sparseScoreOrZero
        );
        Map<String, Double> rawSparseScores = rawScoresByChunkId(
                sparseCandidates.candidates,
                ScoredCandidate::sparseScoreOrZero
        );

        Map<String, ScoredCandidate> representatives = new LinkedHashMap<>();
        for (ScoredCandidate candidate : candidates) {
            representatives.put(candidate.chunkId(), candidate);
        }
        for (ScoredCandidate candidate : sparseCandidates.candidates) {
            representatives.putIfAbsent(candidate.chunkId(), candidate);
        }

        List<ScoredCandidate> fused = new ArrayList<>(representatives.size());
        for (Map.Entry<String, ScoredCandidate> entry : representatives.entrySet()) {
            String chunkId = entry.getKey();
            ScoredCandidate representative = entry.getValue();
            double normalizedDenseScore = denseScores.getOrDefault(chunkId, 0.0d);
            double normalizedSparseScore = sparseScores.getOrDefault(chunkId, 0.0d);
            double fusedScore = normalizedDenseScore * vectorWeight + normalizedSparseScore * keywordWeight;
            fused.add(representative.withScores(
                    representative.scores().afterFusion(
                            normalizedDenseScore,
                            rawSparseScores.get(chunkId),
                            fusedScore
                    )
            ));
        }
        return of(fused).sortByFinalScoreDesc();
    }

    private static Map<String, Double> rawScoresByChunkId(
            List<ScoredCandidate> candidates,
            ToDoubleFunction<ScoredCandidate> scorer
    ) {
        Map<String, Double> rawScores = new LinkedHashMap<>();
        for (ScoredCandidate candidate : candidates) {
            rawScores.merge(candidate.chunkId(), scorer.applyAsDouble(candidate), Math::max);
        }
        return rawScores;
    }

    private static Map<String, Double> normalizeScoresByChunkId(
            List<ScoredCandidate> candidates,
            ToDoubleFunction<ScoredCandidate> scorer
    ) {
        return normalizeScores(rawScoresByChunkId(candidates, scorer));
    }

    private static Map<String, Double> normalizeScores(Map<String, Double> rawScores) {
        if (rawScores.isEmpty()) {
            return Map.of();
        }
        if (rawScores.size() == 1) {
            Map.Entry<String, Double> onlyEntry = rawScores.entrySet().iterator().next();
            return Map.of(onlyEntry.getKey(), 1.0d);
        }

        double min = rawScores.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0d);
        double max = rawScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0d);
        double range = max - min;
        if (range < 1e-9) {
            return rawScores.keySet().stream().collect(java.util.stream.Collectors.toMap(
                    key -> key,
                    key -> 1.0d,
                    (left, right) -> left,
                    LinkedHashMap::new
            ));
        }
        return rawScores.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                entry -> (entry.getValue() - min) / range,
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

}
