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
import com.cffex.rag.retrievalengine.domain.DenseVectorSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;

@ExtendWith(MockitoExtension.class)
class MilvusVectorSearchAdapterTest {

    @Mock
    private MilvusClientV2 milvusClient;

    @Mock
    private RetrievalDebugTraceWriter debugTraceWriter;

    private MilvusVectorSearchAdapter adapter;
    private MilvusProperties properties;
    private MilvusSearchSupport searchSupport;
    private MilvusSearchRequestFactory searchRequestFactory;

    @BeforeEach
    void setUp() {
        properties = new MilvusProperties(
                "http://127.0.0.1:19530",
                "root",
                "Milvus",
                "default",
                "page_content",
                "metadata",
                "vector"
        );
        searchSupport = new MilvusSearchSupport(properties, new ObjectMapper());
        searchRequestFactory = new MilvusSearchRequestFactory(properties, searchSupport);
        adapter = new MilvusVectorSearchAdapter(
                milvusClient,
                searchSupport,
                searchRequestFactory,
                debugTraceWriter
        );
    }

    @Test
    void search_usesPrecomputedQueryVectorAndPushesFilterIntoMilvusRequest() {
        when(milvusClient.search(org.mockito.ArgumentMatchers.any(SearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of(SearchResp.SearchResult.builder()
                        .id("chunk-1")
                        .score(0.91f)
                        .entity(Map.of(
                                "page_content", "content-1",
                                "metadata", "{\"document_id\":\"doc-1\"}"
                        ))
                        .build())))
                .build());

        List<RetrievalCandidate> candidates = adapter.search(new DenseVectorSearchRequest(
                "kb-1",
                "collection-1",
                List.of("doc-1", "doc-2"),
                new float[]{0.1f, 0.2f},
                3
        ));

        ArgumentCaptor<SearchReq> requestCaptor = ArgumentCaptor.forClass(SearchReq.class);
        verify(milvusClient).search(requestCaptor.capture());
        SearchReq searchReq = requestCaptor.getValue();

        assertThat(searchReq.getCollectionName()).isEqualTo("collection-1");
        assertThat(searchReq.getAnnsField()).isEqualTo("vector");
        assertThat(searchReq.getTopK()).isEqualTo(3);
        assertThat(searchReq.getFilter()).isEqualTo("metadata[\"document_id\"] in [\"doc-1\", \"doc-2\"]");
        assertThat(searchReq.getOutputFields()).containsExactly("page_content", "metadata");
        assertThat(searchReq.getData()).hasSize(1);

        assertThat(candidates)
                .extracting(RetrievalCandidate::chunkId, RetrievalCandidate::documentId, RetrievalCandidate::content,
                        RetrievalCandidate::finalScore)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chunk-1", "doc-1", "content-1", 0.9100000262260437d)
                );
        assertThat(candidates.getFirst().metadata()).containsEntry("knowledge_base_id", "kb-1");
    }

    @Test
    void search_filtersDenseResultsByScoreThresholdUsingMappedMilvusFloatScore() {
        when(milvusClient.search(org.mockito.ArgumentMatchers.any(SearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of(
                        SearchResp.SearchResult.builder()
                                .id("chunk-high")
                                .score(0.91f)
                                .entity(Map.of("page_content", "high", "metadata", "{\"document_id\":\"doc-high\"}"))
                                .build(),
                        SearchResp.SearchResult.builder()
                                .id("chunk-equal")
                                .score(0.8f)
                                .entity(Map.of("page_content", "equal", "metadata", "{\"document_id\":\"doc-equal\"}"))
                                .build()
                )))
                .build());

        List<RetrievalCandidate> candidates = adapter.search(new DenseVectorSearchRequest(
                "kb-1",
                "milvus",
                "collection-1",
                List.of(),
                new float[]{0.1f, 0.2f},
                3,
                true,
                0.8d
        ));

        assertThat(candidates).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-high", "chunk-equal");
    }
}
