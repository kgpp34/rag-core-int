package com.cffex.rag.retrievalengine.infrastructure.vectorstore.milvus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.config.MilvusProperties;
import com.cffex.rag.retrievalengine.domain.FullTextSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;

@ExtendWith(MockitoExtension.class)
class MilvusFullTextSearchAdapterTest {

    @Mock
    private MilvusClientV2 milvusClient;

    @Mock
    private RetrievalDebugTraceWriter debugTraceWriter;

    private MilvusFullTextSearchAdapter adapter;

    @BeforeEach
    void setUp() {
        MilvusProperties properties = new MilvusProperties(
                "http://127.0.0.1:19530",
                "root",
                "Milvus",
                "default",
                "page_content",
                "metadata",
                "vector"
        );
        MilvusSearchSupport searchSupport = new MilvusSearchSupport(properties, new ObjectMapper());
        adapter = new MilvusFullTextSearchAdapter(
                milvusClient,
                searchSupport,
                new MilvusSearchRequestFactory(properties, searchSupport),
                debugTraceWriter
        );
    }

    @Test
    void search_usesSharedFilterAndMapsSparseScore() {
        when(milvusClient.search(org.mockito.ArgumentMatchers.any(SearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of(SearchResp.SearchResult.builder()
                        .id("chunk-s1")
                        .score(0.77f)
                        .entity(Map.of(
                                "page_content", "content-s1",
                                "metadata", "{\"document_id\":\"doc-s1\"}"
                        ))
                        .build())))
                .build());

        List<RetrievalCandidate> candidates = adapter.search(new FullTextSearchRequest(
                "kb-1",
                "collection-1",
                List.of("doc-s1"),
                "search text",
                5
        ));

        ArgumentCaptor<SearchReq> requestCaptor = ArgumentCaptor.forClass(SearchReq.class);
        verify(milvusClient).search(requestCaptor.capture());
        SearchReq searchReq = requestCaptor.getValue();

        assertThat(searchReq.getCollectionName()).isEqualTo("collection-1");
        assertThat(searchReq.getFilter()).isEqualTo("metadata[\"document_id\"] in [\"doc-s1\"]");
        assertThat(searchReq.getOutputFields()).containsExactly("page_content", "metadata");

        assertThat(candidates)
                .extracting(RetrievalCandidate::chunkId, RetrievalCandidate::documentId, RetrievalCandidate::content,
                        RetrievalCandidate::finalScore, RetrievalCandidate::sparseScore)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chunk-s1", "doc-s1", "content-s1", 0.0d, 0.7699999809265137d)
                );
        assertThat(candidates.getFirst().metadata()).containsEntry("knowledge_base_id", "kb-1");
    }

    @Test
    void search_keepsSparseResultsWhenScoreThresholdDisabledLikeDifyFullTextRoute() {
        when(milvusClient.search(org.mockito.ArgumentMatchers.any(SearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of(
                        SearchResp.SearchResult.builder()
                                .id("chunk-high")
                                .score(0.77f)
                                .entity(Map.of("page_content", "high", "metadata", "{\"document_id\":\"doc-high\"}"))
                                .build(),
                        SearchResp.SearchResult.builder()
                                .id("chunk-equal")
                                .score(0.7f)
                                .entity(Map.of("page_content", "equal", "metadata", "{\"document_id\":\"doc-equal\"}"))
                                .build()
                )))
                .build());

        List<RetrievalCandidate> candidates = adapter.search(new FullTextSearchRequest(
                "kb-1",
                "milvus",
                "collection-1",
                List.of(),
                "search text",
                5,
                false,
                0.7d
        ));

        assertThat(candidates).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-high", "chunk-equal");
    }
}
