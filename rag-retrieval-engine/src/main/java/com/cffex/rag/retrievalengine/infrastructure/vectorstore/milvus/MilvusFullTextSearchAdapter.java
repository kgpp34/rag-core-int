package com.cffex.rag.retrievalengine.infrastructure.vectorstore.milvus;

import java.util.List;
import java.util.Objects;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.domain.FullTextSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.port.FullTextSearchPort;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;

/**
 * 基于 Milvus SDK 的全文/稀疏检索适配器。
 *
 * <p>dense/sparse 两条路由都直接走 Milvus SDK，这里负责 sparse/BM25 路由，
 * 供 HYBRID 检索补充关键词召回能力。
 */
@Component
public class MilvusFullTextSearchAdapter implements FullTextSearchPort {

    static final String ENGINE_ID = "milvus";

    private final MilvusClientV2 milvusClient;
    private final MilvusSearchSupport searchSupport;
    private final MilvusSearchRequestFactory searchRequestFactory;
    private final RetrievalDebugTraceWriter debugTraceWriter;

    public MilvusFullTextSearchAdapter(
            @Lazy MilvusClientV2 milvusClient,
            MilvusSearchSupport searchSupport,
            MilvusSearchRequestFactory searchRequestFactory,
            RetrievalDebugTraceWriter debugTraceWriter
    ) {
        this.milvusClient = Objects.requireNonNull(milvusClient, "milvusClient must not be null");
        this.searchSupport = Objects.requireNonNull(searchSupport, "searchSupport must not be null");
        this.searchRequestFactory = Objects.requireNonNull(searchRequestFactory, "searchRequestFactory must not be null");
        this.debugTraceWriter = Objects.requireNonNull(debugTraceWriter, "debugTraceWriter must not be null");
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public List<RetrievalCandidate> search(FullTextSearchRequest request) {
        try {
            SearchReq searchReq = searchRequestFactory.buildSparseSearchRequest(
                    request.collectionName(),
                    request.docIds(),
                    request.candidateK(),
                    request.queryText()
            );
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("milvus.sparse.request", java.util.Map.of(
                        "knowledgeBaseId", request.knowledgeBaseId(),
                        "engineId", request.engineId(),
                        "request", searchRequestFactory.describeSparseSearchRequest(
                                request.collectionName(),
                                request.docIds(),
                                request.candidateK(),
                                request.queryText()
                        )
                ));
            }
            List<RetrievalCandidate> candidates = searchSupport.toSparseCandidates(
                    milvusClient.search(searchReq),
                    request.knowledgeBaseId()
            );
            List<RetrievalCandidate> filteredCandidates = filterByScoreThreshold(
                    candidates,
                    request.scoreThresholdEnabled(),
                    request.scoreThreshold()
            );
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("milvus.sparse.response", java.util.Map.of(
                        "knowledgeBaseId", request.knowledgeBaseId(),
                        "collectionName", request.collectionName(),
                        "scoreThresholdEnabled", request.scoreThresholdEnabled(),
                        "scoreThreshold", request.scoreThreshold(),
                        "rawCount", candidates.size(),
                        "count", filteredCandidates.size(),
                        "candidates", debugTraceWriter.candidates(filteredCandidates)
                ));
            }
            return filteredCandidates;
        } catch (RagServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("milvus.sparse.failed", java.util.Map.of(
                        "knowledgeBaseId", request.knowledgeBaseId(),
                        "collectionName", request.collectionName(),
                        "error", Objects.toString(ex.getMessage(), ex.getClass().getSimpleName())
                ));
            }
            throw MilvusExceptionSupport.retrievalFailed(
                    "sparse ",
                    request.knowledgeBaseId(),
                    request.collectionName(),
                    ex
            );
        }
    }

    private static List<RetrievalCandidate> filterByScoreThreshold(
            List<RetrievalCandidate> candidates,
            boolean enabled,
            double threshold
    ) {
        if (!enabled) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> Objects.requireNonNullElse(candidate.sparseScore(), 0.0d) > threshold)
                .toList();
    }
}
