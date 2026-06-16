package com.cffex.rag.retrievalengine.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.CandidateSet;
import com.cffex.rag.retrievalengine.domain.model.RerankModelPolicy;
import com.cffex.rag.retrievalengine.domain.port.RerankPort;

/**
 * 模型重排序服务：调用外部 rerank 模型打分后排序。
 *
 * <p>连接参数已由规划阶段解析完毕，本服务直接使用领域 {@link RerankModelPolicy}，
 * 不再通过共享检索规格对象透传到底层端口。
 * 输出的 {@code rankingScore} 即 rerank 模型返回的相关性分数。
 */
@Component
public class RerankRankingService {

    private static final Logger log = LoggerFactory.getLogger(RerankRankingService.class);

    private final RerankPort rerankPort;

    public RerankRankingService(RerankPort rerankPort) {
        this.rerankPort = rerankPort;
    }

    public List<RetrievedChunk> rank(String query,
                                     List<RetrievalCandidate> candidates,
                                     RerankGlobalRankingCommand spec,
                                     int topK) {
        return rank(query, candidates, spec, topK, 0.0d);
    }

    public List<RetrievedChunk> rank(String query,
                                     List<RetrievalCandidate> candidates,
                                     RerankGlobalRankingCommand spec,
                                     int topK,
                                     Double scoreThreshold) {
        logGlobalRerankInput(candidates);
        long start = System.nanoTime();
        Map<String, Double> scores = rerank(query, candidates, spec, topK, scoreThreshold);
        long rerankMs = (System.nanoTime() - start) / 1_000_000;

        List<RetrievedChunk> result = candidates.stream()
                .filter(c -> scores.containsKey(c.candidateKey()))
                .sorted(Comparator.comparingDouble(
                        c -> -scores.getOrDefault(c.candidateKey(), Double.NEGATIVE_INFINITY)))
                .limit(topK)
                .map(c -> {
                    double rerankScore = scores.getOrDefault(c.candidateKey(), 0.0);
                    return new RetrievedChunk(
                            c.chunkId(),
                            c.documentId(),
                            c.knowledgeBaseId(),
                            c.finalScore(),
                            c.sparseScore(),
                            rerankScore,
                            c.content(),
                            c.metadata()
                    );
                })
                .toList();

        log.debug("全局rerank完成 | inputCount={}, outputCount={}, ms={}", candidates.size(), result.size(), rerankMs);
        logGlobalRerankOutput(result);
        return result;
    }

    /**
     * 单库 rerank：返回保留原始 sparse 分、并把 rerank 分回写到 vectorScore 的候选列表。
     *
     * <p>这样下游兼容路径仍可继续把 {@code vectorScore} 视为当前阶段的最终排序分。
     */
    public List<RetrievalCandidate> rerankCandidates(String query,
                                                     List<RetrievalCandidate> candidates,
                                                     RerankGlobalRankingCommand spec,
                                                     int topK) {
        return rerankCandidates(query, candidates, spec, topK, 0.0d);
    }

    public List<RetrievalCandidate> rerankCandidates(String query,
                                                     List<RetrievalCandidate> candidates,
                                                     RerankGlobalRankingCommand spec,
                                                     int topK,
                                                     Double scoreThreshold) {
        long start = System.nanoTime();
        Map<String, Double> scores = rerank(query, candidates, spec, topK, scoreThreshold);
        long rerankMs = (System.nanoTime() - start) / 1_000_000;

        List<RetrievalCandidate> result = CandidateSet.of(candidates.stream()
                .filter(c -> scores.containsKey(c.candidateKey()))
                .sorted(Comparator.comparingDouble(
                        c -> -scores.getOrDefault(c.candidateKey(), Double.NEGATIVE_INFINITY)))
                .map(c -> c.toScoredCandidate().reranked(scores.getOrDefault(c.candidateKey(), 0.0d)))
                .toList()).toLegacyCandidates();

        log.debug("单库rerank完成 | inputCount={}, outputCount={}, ms={}", candidates.size(), result.size(), rerankMs);
        return result;
    }

    private Map<String, Double> rerank(String query,
                                       List<RetrievalCandidate> candidates,
                                       RerankGlobalRankingCommand spec,
                                       int topK,
                                       Double scoreThreshold) {
        return rerankPort.rerank(query, toRerankModelPolicy(spec), candidates, topK, scoreThreshold);
    }

    private static RerankModelPolicy toRerankModelPolicy(RerankGlobalRankingCommand spec) {
        return new RerankModelPolicy(
                spec.modelEndpoint().endpoint(),
                spec.modelEndpoint().authToken(),
                spec.modelEndpoint().model()
        );
    }

    private void logGlobalRerankInput(List<RetrievalCandidate> candidates) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("全局rerank输入候选 | count={}, top={}",
                candidates.size(),
                candidates.stream()
                        .limit(100)
                        .map(RerankRankingService::candidateSummary)
                        .toList());
    }

    private void logGlobalRerankOutput(List<RetrievedChunk> chunks) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("全局rerank输出候选 | count={}, top={}",
                chunks.size(),
                chunks.stream()
                        .limit(100)
                        .map(RerankRankingService::chunkSummary)
                        .toList());
    }

    private static String candidateSummary(RetrievalCandidate candidate) {
        return "kbId=" + candidate.knowledgeBaseId()
                + ", chunkId=" + candidate.chunkId()
                + ", documentId=" + candidate.documentId()
                + ", docId=" + metadataValue(candidate.metadata(), "doc_id")
                + ", score=" + candidate.finalScore()
                + ", sparseScore=" + candidate.sparseScore()
                + ", text=" + abbreviate(candidate.content(), 80);
    }

    private static String chunkSummary(RetrievedChunk chunk) {
        return "kbId=" + chunk.knowledgeBaseId()
                + ", chunkId=" + chunk.chunkId()
                + ", documentId=" + chunk.documentId()
                + ", docId=" + metadataValue(chunk.metadata(), "doc_id")
                + ", vectorScore=" + chunk.vectorScore()
                + ", sparseScore=" + chunk.sparseScore()
                + ", rankingScore=" + chunk.rankingScore()
                + ", text=" + abbreviate(chunk.content(), 80);
    }

    private static Object metadataValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value != null) {
            return value;
        }
        Object nestedMetadata = metadata.get("metadata");
        if (nestedMetadata instanceof Map<?, ?> nested) {
            return nested.get(key);
        }
        return null;
    }

    private static String abbreviate(String text, int maxLength) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }
}
