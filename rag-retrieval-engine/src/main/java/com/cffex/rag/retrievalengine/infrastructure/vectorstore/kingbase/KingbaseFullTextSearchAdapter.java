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
import com.cffex.rag.retrievalengine.domain.FullTextSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.port.FullTextSearchPort;

/**
 * 基于 Kingbase 的全文检索适配器（PostgreSQL 兼容的 {@code to_tsvector/plainto_tsquery}）。
 *
 * <p>全文检索的配置名由 {@code kingbase.fts-config} 决定：英文语料用 {@code simple}，
 * 中文语料需要在库里执行 {@code CREATE EXTENSION sys_jieba} 后配置成 {@code jiebacfg}。
 * 配置名会以字面量形式拼进 SQL，这样查询表达式才能命中 Dify 建好的
 * {@code gin (to_tsvector('cfg', text))} 表达式索引。
 */
@Component
@ConditionalOnProperty(prefix = "kingbase", name = "url")
public class KingbaseFullTextSearchAdapter implements FullTextSearchPort {

    static final String ENGINE_ID = "kingbase";

    private final JdbcTemplate jdbcTemplate;
    private final KingbaseSearchSupport searchSupport;
    private final RetrievalDebugTraceWriter debugTraceWriter;

    public KingbaseFullTextSearchAdapter(
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
    public List<RetrievalCandidate> search(FullTextSearchRequest request) {
        try {
            String tableName = searchSupport.tableName(request.collectionName());
            List<String> docIds = request.docIds();
            String sql = searchSupport.buildFullTextSql(tableName, docIds, request.candidateK());
            List<Object> arguments = new ArrayList<>(docIds.size() + 2);
            arguments.add(request.queryText());
            arguments.add(request.queryText());
            arguments.addAll(docIds);
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("kingbase.fulltext.request", Map.of(
                        "knowledgeBaseId", request.knowledgeBaseId(),
                        "engineId", request.engineId(),
                        "query", searchSupport.describeFullTextQuery(tableName, docIds, request.candidateK()),
                        "queryText", request.queryText()
                ));
            }
            List<RetrievalCandidate> candidates = jdbcTemplate.query(
                    sql,
                    searchSupport.fullTextRowMapper(request.knowledgeBaseId()),
                    arguments.toArray()
            );
            List<RetrievalCandidate> filteredCandidates = searchSupport.filterFullTextByScoreThreshold(
                    candidates,
                    request.scoreThresholdEnabled(),
                    request.scoreThreshold()
            );
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("kingbase.fulltext.response", Map.of(
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
                debugTraceWriter.record("kingbase.fulltext.failed", Map.of(
                        "knowledgeBaseId", request.knowledgeBaseId(),
                        "collectionName", request.collectionName(),
                        "error", KingbaseExceptionSupport.summarize(ex)
                ));
            }
            throw KingbaseExceptionSupport.retrievalFailed(
                    "fulltext ",
                    request.knowledgeBaseId(),
                    request.collectionName(),
                    ex
            );
        }
    }
}
