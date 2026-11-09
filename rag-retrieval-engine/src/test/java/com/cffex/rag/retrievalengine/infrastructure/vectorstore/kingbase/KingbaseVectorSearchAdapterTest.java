package com.cffex.rag.retrievalengine.infrastructure.vectorstore.kingbase;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.config.KingbaseProperties;
import com.cffex.rag.retrievalengine.domain.DenseVectorSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KingbaseVectorSearchAdapterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private RetrievalDebugTraceWriter debugTraceWriter;

    private KingbaseVectorSearchAdapter adapter;

    @BeforeEach
    void setUp() {
        KingbaseSearchSupport searchSupport = new KingbaseSearchSupport(new KingbaseProperties(
                "jdbc:postgresql://127.0.0.1:54321/dify",
                "system",
                "123456",
                "org.postgresql.Driver",
                "embedding_",
                "simple",
                100,
                10
        ), new ObjectMapper());
        adapter = new KingbaseVectorSearchAdapter(jdbcTemplate, searchSupport, debugTraceWriter);
    }

    @Test
    void exposesKingbaseEngineId() {
        assertThat(adapter.engineId()).isEqualTo("kingbase");
    }

    @Test
    void filtersCandidatesByScoreThresholdWhenEnabled() {
        when(jdbcTemplate.query(anyString(), anyRowMapper(), any(Object[].class)))
                .thenReturn(List.of(candidate("a", 0.9d), candidate("b", 0.4d)));

        List<RetrievalCandidate> result = adapter.search(new DenseVectorSearchRequest(
                "kb-1",
                "kingbase",
                "Vector_index_abc_Node",
                List.of("doc-1"),
                new float[]{0.1f, 0.2f},
                20,
                true,
                0.5d
        ));

        assertThat(result).extracting(RetrievalCandidate::chunkId).containsExactly("a");
    }

    @Test
    void keepsAllCandidatesWhenThresholdDisabled() {
        when(jdbcTemplate.query(anyString(), anyRowMapper(), any(Object[].class)))
                .thenReturn(List.of(candidate("a", 0.9d), candidate("b", 0.4d)));

        List<RetrievalCandidate> result = adapter.search(new DenseVectorSearchRequest(
                "kb-1",
                "kingbase",
                "Vector_index_abc_Node",
                List.of(),
                new float[]{0.1f, 0.2f},
                20
        ));

        assertThat(result).hasSize(2);
    }

    @Test
    void wrapsStorageFailureIntoRagServiceException() {
        when(jdbcTemplate.query(anyString(), anyRowMapper(), any(Object[].class)))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> adapter.search(new DenseVectorSearchRequest(
                "kb-1",
                "kingbase",
                "Vector_index_abc_Node",
                List.of(),
                new float[]{0.1f},
                20
        )))
                .isInstanceOf(RagServiceException.class)
                .satisfies(ex -> assertThat(((RagServiceException) ex).errorCode())
                        .isEqualTo(RagErrorCode.RETRIEVAL_FAILED))
                .hasMessageContaining("connection refused");
    }

    @Test
    void rejectsIllegalCollectionNameBeforeTouchingDatabase() {
        assertThatThrownBy(() -> adapter.search(new DenseVectorSearchRequest(
                "kb-1",
                "kingbase",
                "Vector_index_abc_Node; drop table x; --",
                List.of(),
                new float[]{0.1f},
                20
        )))
                .isInstanceOf(RagServiceException.class)
                .satisfies(ex -> assertThat(((RagServiceException) ex).errorCode())
                        .isEqualTo(RagErrorCode.INVALID_CONFIGURATION));

        verifyNoInteractions(jdbcTemplate);
    }

    @SuppressWarnings("unchecked")
    private static RowMapper<RetrievalCandidate> anyRowMapper() {
        return ArgumentMatchers.any(RowMapper.class);
    }

    private static RetrievalCandidate candidate(String chunkId, double score) {
        return new RetrievalCandidate(chunkId, "doc-1", "kb-1", "content", score, null, Map.of());
    }
}
