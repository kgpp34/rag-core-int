package com.cffex.rag.retrievalengine.domain.port;

import java.util.List;
import java.util.Map;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.RerankModelPolicy;

/**
 * Rerank 模型出站端口：返回 candidateKey → 重排分数的映射。
 */
public interface RerankPort {
    Map<String, Double> rerank(String query, RerankModelPolicy policy,
                               List<RetrievalCandidate> candidates, int topN, Double scoreThreshold);
}
