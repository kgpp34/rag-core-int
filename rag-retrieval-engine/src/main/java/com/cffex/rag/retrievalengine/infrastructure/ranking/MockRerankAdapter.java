package com.cffex.rag.retrievalengine.infrastructure.ranking;

import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.RerankModelPolicy;
import com.cffex.rag.retrievalengine.domain.port.RerankPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅用于单元测试 / 本地开发调试，不注册为 Spring Bean。生产环境由 HttpRerankAdapter 提供。 */
public class MockRerankAdapter implements RerankPort {
    @Override
    public Map<String, Double> rerank(String query,
                                      RerankModelPolicy policy,
                                      List<RetrievalCandidate> candidates,
                                      int topN,
                                      Double scoreThreshold) {
        Map<String, Double> scores = new LinkedHashMap<>();
        double base = Math.max(0.1d, query.length() / 100.0d);
        for (int index = 0; index < candidates.size(); index++) {
            RetrievalCandidate candidate = candidates.get(index);
            scores.put(candidate.candidateKey(), candidate.vectorScore() + base - index * 0.01d);
        }
        return Map.copyOf(scores);
    }
}
