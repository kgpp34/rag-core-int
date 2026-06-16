package com.cffex.rag.metadatacacher.infrastructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cffex.rag.common.domain.metadata.BusinessDomain;
import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition;
import com.cffex.rag.common.domain.metadata.MetadataSnapshot;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.ModelQueryCondition;
import com.cffex.rag.common.domain.metadata.ModelType;
import com.cffex.rag.common.domain.metadata.RetrievalMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.cffex.rag.metadatacacher.application.MetadataCacheQueryService;
import org.junit.jupiter.api.Test;

class MetadataCacheQueryServiceTest {

    @Test
    void listModels_filtersByTypeAndName() {
        InMemoryMetadataSnapshotStore snapshotStore = new InMemoryMetadataSnapshotStore();
        snapshotStore.put(new MetadataSnapshot(
                1L,
                Instant.now(),
                Map.of(),
                Map.of(
                        "model-1", new ModelMeta("model-1", "text-model", ModelType.LLM, "http://llm", "k1", true),
                        "model-2", new ModelMeta("model-2", "embed-model", ModelType.EMBEDDING, "http://embed", "k2", true),
                        "model-3", new ModelMeta("model-3", "other-llm", ModelType.LLM, "http://llm2", "k3", false)
                ),
                Map.of(),
                Map.of(),
                Map.of()
        ));

        MetadataCacheQueryService service = new MetadataCacheQueryService(snapshotStore);

        List<ModelMeta> filtered = service.listModels(new ModelQueryCondition(ModelType.LLM, "text-model"));

        assertEquals(1, filtered.size());
        assertEquals("model-1", filtered.get(0).modelId());
    }

    @Test
    void listKnowledgeBases_filtersByDocumentIds() {
        InMemoryMetadataSnapshotStore snapshotStore = new InMemoryMetadataSnapshotStore();
        snapshotStore.put(buildSnapshot(Map.of(
                "doc-a", "kb-1",
                "doc-b", "kb-2"
        )));
        MetadataCacheQueryService service = new MetadataCacheQueryService(snapshotStore);

        List<KnowledgeBaseMeta> filtered = service.listKnowledgeBases(
                new KnowledgeBaseQueryCondition(List.of("doc-a"))
        );

        assertEquals(1, filtered.size());
        assertEquals("kb-1", filtered.get(0).knowledgeBaseId());
    }

    @Test
    void listKnowledgeBases_handlesLargeDocumentIdBatch() {
        Map<String, String> documentMappings = new LinkedHashMap<>();
        IntStream.range(0, 12000).forEach(i -> documentMappings.put("doc-" + UUID.randomUUID(), "kb-1"));

        InMemoryMetadataSnapshotStore snapshotStore = new InMemoryMetadataSnapshotStore();
        snapshotStore.put(buildSnapshot(documentMappings));
        MetadataCacheQueryService service = new MetadataCacheQueryService(snapshotStore);
        // 测试耗时
        long start = System.currentTimeMillis();
        List<KnowledgeBaseMeta> filtered = service.listKnowledgeBases(
                new KnowledgeBaseQueryCondition(List.copyOf(documentMappings.keySet()))
        );
        long end = System.currentTimeMillis();
        System.out.println("执行耗时：" + (end - start) + " ms");

        assertEquals(1, filtered.size());
        assertEquals("kb-1", filtered.get(0).knowledgeBaseId());
    }

    @Test
    void getDocumentMetas_returnsCachedDocumentInfo() {
        InMemoryMetadataSnapshotStore snapshotStore = new InMemoryMetadataSnapshotStore();
        snapshotStore.put(buildSnapshot(Map.of("doc-a", "kb-1")));
        MetadataCacheQueryService service = new MetadataCacheQueryService(snapshotStore);

        Map<String, DocumentMeta> metas = service.getDocumentMetas(List.of("doc-a", "missing"));

        assertEquals(1, metas.size());
        assertEquals("Document A", metas.get("doc-a").name());
        assertEquals("file-a", metas.get("doc-a").uploadFileId());
    }

    private MetadataSnapshot buildSnapshot(Map<String, String> documentMappings) {
        return new MetadataSnapshot(
                1L,
                Instant.now(),
                Map.of(
                        "kb-1", new KnowledgeBaseMeta("kb-1", RetrievalMode.HYBRID, "collection-1", true),
                        "kb-2", new KnowledgeBaseMeta("kb-2", RetrievalMode.HYBRID, "collection-2", true)
                ),
                Map.of(),
                documentMappings,
                Map.of(
                        "doc-a", new DocumentMeta("doc-a", "kb-1", "Document A", "file-a"),
                        "doc-b", new DocumentMeta("doc-b", "kb-2", "Document B", "file-b")
                ),
                Map.of(
                        BusinessDomain.POLICY, List.of("kb-1"),
                        BusinessDomain.RD_DATA, List.of("kb-2")
                )
        );
    }
}
