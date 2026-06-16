package com.cffex.rag.retrievalengine.infrastructure.vectorstore.milvus;

import java.util.List;
import java.util.Objects;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.domain.DenseVectorSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.port.VectorSearchPort;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;

/**
 * 基于 Milvus SDK 的 dense 向量检索适配器。
 */
@Component
public class MilvusVectorSearchAdapter implements VectorSearchPort {

    static final String ENGINE_ID = "milvus";

    private final MilvusClientV2 milvusClient;
    private final MilvusSearchSupport searchSupport;
    private final MilvusSearchRequestFactory searchRequestFactory;
    private final RetrievalDebugTraceWriter debugTraceWriter;

    public MilvusVectorSearchAdapter(
            @Lazy MilvusClientV2 milvusClient,
            MilvusSearchSupport searchSupport,
            MilvusSearchRequestFactory searchRequestFactory,
            RetrievalDebugTraceWriter debugTraceWriter
    ) {
        this.milvusClient = Objects.requireNonNull(milvusClient);
        this.searchSupport = Objects.requireNonNull(searchSupport);
        this.searchRequestFactory = Objects.requireNonNull(searchRequestFactory);
        this.debugTraceWriter = Objects.requireNonNull(debugTraceWriter);
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public List<RetrievalCandidate> search(DenseVectorSearchRequest request) {
        try {
            float[] queryVector = request.queryVector();
            SearchReq searchReq = searchRequestFactory.buildDenseSearchRequest(
                    request.collectionName(),
                    request.docIds(),
                    request.candidateK(),
                    queryVector
            );
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("milvus.dense.request", java.util.Map.of(
                        "knowledgeBaseId", request.knowledgeBaseId(),
                        "engineId", request.engineId(),
                        "request", searchRequestFactory.describeDenseSearchRequest(
                                request.collectionName(),
                                request.docIds(),
                                request.candidateK(),
                                queryVector
                        ),
                        "queryVector", debugTraceWriter.embeddingSummary(queryVector)
                ));
            }
            List<RetrievalCandidate> candidates = searchSupport.toDenseCandidates(
                    milvusClient.search(searchReq),
                    request.knowledgeBaseId()
            );
            List<RetrievalCandidate> filteredCandidates = filterByScoreThreshold(
                    candidates,
                    request.scoreThresholdEnabled(),
                    request.scoreThreshold()
            );
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("milvus.dense.response", java.util.Map.of(
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
                debugTraceWriter.record("milvus.dense.failed", java.util.Map.of(
                        "knowledgeBaseId", request.knowledgeBaseId(),
                        "collectionName", request.collectionName(),
                        "error", Objects.toString(ex.getMessage(), ex.getClass().getSimpleName())
                ));
            }
            throw MilvusExceptionSupport.retrievalFailed(
                    "dense ",
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
                .filter(candidate -> candidate.finalScore() > threshold)
                .toList();
    }
}
