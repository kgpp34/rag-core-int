package com.cffex.rag.retrievalengine.infrastructure.vectorstore.kingbase;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.retrievalengine.config.KingbaseProperties;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;

class KingbaseSearchSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private KingbaseSearchSupport support(String ftsConfig) {
        return new KingbaseSearchSupport(new KingbaseProperties(
                "jdbc:postgresql://127.0.0.1:54321/dify",
                "system",
                "123456",
                "org.postgresql.Driver",
                "embedding_",
                ftsConfig,
                100,
                10
        ), objectMapper);
    }

    @Test
    void buildsDenseQueryWithCosineDistanceAndNullsLast() {
        String sql = support("simple").buildDenseSql("embedding_Vector_index_abc_Node", List.of(), 20);

        assertThat(sql).isEqualTo(
                "SELECT id, text, meta, (embedding <=> ?::vector) AS distance FROM embedding_Vector_index_abc_Node"
                        + " ORDER BY distance NULLS LAST LIMIT 20"
        );
    }

    @Test
    void buildsDenseQueryWithDocumentFilter() {
        String sql = support("simple").buildDenseSql("embedding_t1", List.of("doc-1", "doc-2"), 20);

        assertThat(sql).contains("WHERE meta->>'document_id' IN (?, ?)");
        assertThat(sql).endsWith("ORDER BY distance NULLS LAST LIMIT 20");
        assertThat(countPlaceholders(sql)).isEqualTo(3);
    }

    @Test
    void buildsFullTextQueryWithExplicitTextSearchConfiguration() {
        String sql = support("jiebacfg").buildFullTextSql("embedding_t1", List.of("doc-1"), 5);

        // 必须是 OR 语义：plainto_tsquery 的 AND 会让中文长问句几乎 0 命中
        String tsQuery = "to_tsquery('jiebacfg', replace(plainto_tsquery('jiebacfg', ?)::text, ' & ', ' | '))";
        assertThat(sql).contains("ts_rank(to_tsvector('jiebacfg', text), " + tsQuery + ") AS score");
        assertThat(sql).contains("WHERE to_tsvector('jiebacfg', text) @@ " + tsQuery);
        assertThat(sql).doesNotContain("@@ plainto_tsquery");
        assertThat(sql).contains("AND meta->>'document_id' IN (?)");
        assertThat(sql).endsWith("ORDER BY score DESC LIMIT 5");
        assertThat(countPlaceholders(sql)).isEqualTo(3);
    }

    @Test
    void rejectsFullTextSearchWhenConfigurationIsEmpty() {
        KingbaseSearchSupport support = support("");

        assertThat(support.fullTextEnabled()).isFalse();
        assertThatThrownBy(() -> support.buildFullTextSql("embedding_t1", List.of(), 5))
                .isInstanceOf(RagServiceException.class)
                .hasMessageContaining("kingbase.fts-config");
    }

    @Test
    void rejectsIllegalCollectionNameToAvoidSqlInjection() {
        KingbaseSearchSupport support = support("simple");

        assertThatThrownBy(() -> support.tableName("x; drop table embedding_t1; --"))
                .isInstanceOf(RagServiceException.class)
                .satisfies(ex -> assertThat(((RagServiceException) ex).errorCode())
                        .isEqualTo(RagErrorCode.INVALID_CONFIGURATION));
    }

    @Test
    void rejectsIllegalTextSearchConfigurationName() {
        assertThatThrownBy(() -> support("simple' ; drop table t; --").buildFullTextSql("embedding_t1", List.of(), 5))
                .isInstanceOf(RagServiceException.class)
                .satisfies(ex -> assertThat(((RagServiceException) ex).errorCode())
                        .isEqualTo(RagErrorCode.INVALID_CONFIGURATION));
    }

    @Test
    void formatsVectorAsKesVectorLiteral() {
        assertThat(support("simple").formatVector(new float[]{0.5f, -1.25f, 2.0f}))
                .isEqualTo("[0.5,-1.25,2.0]");
    }

    @Test
    void mapsDenseRowToCandidateWithCosineSimilarityScore() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("id")).thenReturn("node-1");
        when(resultSet.getString("text")).thenReturn("hello world");
        when(resultSet.getString("meta")).thenReturn("{\"document_id\":\"doc-1\",\"doc_id\":\"node-1\"}");
        when(resultSet.getDouble("distance")).thenReturn(0.25d);

        RetrievalCandidate candidate = support("simple").denseRowMapper("kb-1").mapRow(resultSet, 0);

        assertThat(candidate.chunkId()).isEqualTo("node-1");
        assertThat(candidate.documentId()).isEqualTo("doc-1");
        assertThat(candidate.knowledgeBaseId()).isEqualTo("kb-1");
        assertThat(candidate.content()).isEqualTo("hello world");
        assertThat(candidate.vectorScore()).isEqualTo(0.75d);
        assertThat(candidate.sparseScore()).isNull();
        assertThat(candidate.metadata()).containsEntry("knowledge_base_id", "kb-1");
    }

    @Test
    void mapsFullTextRowToCandidateWithSparseScore() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("id")).thenReturn("node-2");
        when(resultSet.getString("text")).thenReturn("hello world");
        when(resultSet.getString("meta")).thenReturn("{\"document_id\":\"doc-2\"}");
        when(resultSet.getDouble("score")).thenReturn(0.4d);

        RetrievalCandidate candidate = support("simple").fullTextRowMapper("kb-1").mapRow(resultSet, 0);

        assertThat(candidate.chunkId()).isEqualTo("node-2");
        assertThat(candidate.vectorScore()).isZero();
        assertThat(candidate.sparseScore()).isEqualTo(0.4d);
    }

    @Test
    void filtersByScoreThresholdOnlyWhenEnabled() {
        KingbaseSearchSupport support = support("simple");
        List<RetrievalCandidate> candidates = List.of(
                candidate("a", 0.9d, null),
                candidate("b", 0.5d, null),
                candidate("c", 0.1d, null)
        );

        assertThat(support.filterDenseByScoreThreshold(candidates, false, 0.9d)).hasSize(3);
        assertThat(support.filterDenseByScoreThreshold(candidates, true, 0.5d))
                .extracting(RetrievalCandidate::chunkId)
                .containsExactly("a");
    }

    private static RetrievalCandidate candidate(String chunkId, double vectorScore, Double sparseScore) {
        return new RetrievalCandidate(chunkId, "doc", "kb", "content", vectorScore, sparseScore, java.util.Map.of());
    }

    private static int countPlaceholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }
}
