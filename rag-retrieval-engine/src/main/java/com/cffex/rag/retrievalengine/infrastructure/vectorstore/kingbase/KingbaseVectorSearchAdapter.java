package com.cffex.rag.retrievalengine.infrastructure.vectorstore.kingbase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.domain.DenseVectorSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.port.VectorSearchPort;

/**
 * 基于 Kingbase（KES_Vector 扩展）的 dense 向量检索适配器。
 *
 * <p>检索语义与 Dify 的 pgvector/kingbase 实现对齐：余弦距离 {@code <=>} 升序取前 K，
 * 分数换算为 {@code 1 - distance}，即越大越相似。
 */
@Component
@ConditionalOnProperty(prefix = "kingbase", name = "url")
public class KingbaseVectorSearchAdapter implements VectorSearchPort {

    static final String ENGINE_ID = "kingbase";

    private final JdbcTemplate jdbcTemplate;
    private final KingbaseSearchSupport searchSupport;
    private final RetrievalDebugTraceWriter debugTraceWriter;

    public KingbaseVectorSearchAdapter(
            @Qualifier("kingbaseJdbcTemplate") JdbcTemplate jdbcTemplate,
            KingbaseSearchSupport searchSupport,
            RetrievalDebugTraceWriter debugTraceWriter
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.searchSupport = Objects.requireNonNull(searchSupport, "searchSupport must not be null");
        this.debugTraceWriter = Objects.requireNonNull(debugTraceWriter, "debugTraceWriter must not be null");
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public List<RetrievalCandidate> search(DenseVectorSearchRequest request) {
        try {
            String tableName = searchSupport.tableName(request.collectionName());
            List<String> docIds = request.docIds();
            String sql = searchSupport.buildDenseSql(tableName, docIds, request.candidateK());
            List<Object> arguments = new ArrayList<>(docIds.size() + 1);
            arguments.add(searchSupport.formatVector(request.queryVector()));
            arguments.addAll(docIds);
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("kingbase.dense.request", Map.of(
                        "knowledgeBaseId", request.knowledgeBaseId(),
                        "engineId", request.engineId(),
                        "query", searchSupport.describeDenseQuery(
                                tableName,
                                docIds,
                                request.candidateK(),
                                request.queryVector()
                        ),
                        "queryVector", debugTraceWriter.embeddingSummary(request.queryVector())
                ));
            }
            List<RetrievalCandidate> candidates = jdbcTemplate.query(
                    sql,
                    searchSupport.denseRowMapper(request.knowledgeBaseId()),
                    arguments.toArray()
            );
            List<RetrievalCandidate> filteredCandidates = searchSupport.filterDenseByScoreThreshold(
                    candidates,
                    request.scoreThresholdEnabled(),
                    request.scoreThreshold()
            );
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("kingbase.dense.response", Map.of(
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
                debugTraceWriter.record("kingbase.dense.failed", Map.of(
                        "knowledgeBaseId", request.knowledgeBaseId(),
                        "collectionName", request.collectionName(),
                        "error", KingbaseExceptionSupport.summarize(ex)
                ));
            }
            throw KingbaseExceptionSupport.retrievalFailed(
                    "dense ",
                    request.knowledgeBaseId(),
                    request.collectionName(),
                    ex
            );
        }
    }
}
