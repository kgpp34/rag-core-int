package com.cffex.rag.metadatacacher.infrastructure.persistence;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import org.junit.jupiter.api.Test;

import com.cffex.rag.common.domain.metadata.BusinessDomain;
import com.cffex.rag.common.domain.retrieval.RetrievalCapability;
import com.cffex.rag.metadatacacher.config.MetadataCacherProperties;
import com.cffex.rag.metadatacacher.domain.MetadataPayload;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyDatasetMapper;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyDocumentMapper;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyProviderModelMapper;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyTagBindingMapper;

class DifyMetadataSourceTest {

    @Test
    void load_filtersModelsByConfiguredTenant_andBuildsDomainMappingsFromConfiguredTags() {
        MetadataCacherProperties properties = new MetadataCacherProperties();
        properties.getDify().setDefaultTenantId("tenant-a");
        MetadataCacherProperties.DomainMapping rdDataDomain = new MetadataCacherProperties.DomainMapping();
        rdDataDomain.setDomainCode("rd-data");
        rdDataDomain.setTagCodes(List.of("rd-data"));
        properties.getDify().setDomainMappings(List.of(rdDataDomain));

        FakeDatasetMapper datasetMapper = new FakeDatasetMapper(List.of(
                new DifyDatasetMapper.DatasetRow("kb-1", "知识库一", "hybrid_search", "collection-1", true,
                        15, true, true, 0.4d, 0.8d, 0.2d),
                new DifyDatasetMapper.DatasetRow("kb-2", "知识库二", "hybrid_search", "collection-2", true,
                        null, false, false, 0.0d, 0.7d, 0.3d)
        ));
        FakeDocumentMapper documentMapper = new FakeDocumentMapper(List.of(
                new DifyDocumentMapper.DocumentRow("doc-1", "kb-1", "document-a", "{\"upload_file_id\":\"file-123\"}")
        ));
        FakeProviderModelMapper providerModelMapper = new FakeProviderModelMapper(List.of(
                new DifyProviderModelMapper.ProviderModelRow("model-1", "tenant-a", "openai", "text-embedding-3-small", "text_embedding", "{}", true)
        ));
        FakeTagBindingMapper tagBindingMapper = new FakeTagBindingMapper(List.of(
                new DifyTagBindingMapper.TagBindingRow("rd-data", "kb-2")
        ));

        DifyMetadataSource source = new DifyMetadataSource(
                datasetMapper,
                documentMapper,
                providerModelMapper,
                tagBindingMapper,
                properties
        );

        MetadataPayload payload = source.load();

        assertEquals("tenant-a", providerModelMapper.requestedTenantId);
        assertEquals(List.of("rd-data"), tagBindingMapper.requestedTagCodes);
        assertEquals(List.of("kb-2"), payload.domainToKnowledgeBases().get(BusinessDomain.RD_DATA));
        assertEquals(List.of("kb-1"), payload.domainToKnowledgeBases().get(BusinessDomain.UNKNOWN));
        assertEquals(1, payload.modelsById().size());
        assertEquals("document-a", payload.documentMetasById().get("doc-1").name());
        assertEquals("file-123", payload.documentMetasById().get("doc-1").uploadFileId());
        assertEquals(15, payload.knowledgeBasesById().get("kb-1").topK());
        assertEquals("知识库一", payload.knowledgeBasesById().get("kb-1").name());
        assertEquals(true, payload.knowledgeBasesById().get("kb-1").rerankingEnabled());
        assertEquals(true, payload.knowledgeBasesById().get("kb-1").scoreThresholdEnabled());
        assertEquals(0.4d, payload.knowledgeBasesById().get("kb-1").scoreThreshold());
        assertIterableEquals(
                List.of(RetrievalCapability.VECTOR, RetrievalCapability.FULL_TEXT),
                payload.knowledgeBasesById().get("kb-1").bindings().stream()
                        .map(binding -> binding.capability())
                        .toList()
        );
        assertEquals(
                "collection-1",
                payload.knowledgeBasesById().get("kb-1").bindingOf(RetrievalCapability.VECTOR).targetName()
        );
    }

    @Test
    void load_prefersConfiguredDefaultApiKeyForAllModelTypes() {
        MetadataCacherProperties properties = new MetadataCacherProperties();
        properties.getDify().setDefaultTenantId("tenant-a");
        properties.getDify().setDefaultApiKey("plain-default-token");

        FakeDatasetMapper datasetMapper = new FakeDatasetMapper(List.of());
        FakeDocumentMapper documentMapper = new FakeDocumentMapper(List.of());
        FakeProviderModelMapper providerModelMapper = new FakeProviderModelMapper(List.of(
                new DifyProviderModelMapper.ProviderModelRow(
                        "embed-model", "tenant-a", "openai", "bge-m3", "text_embedding",
                        "{\"api_key\":\"encrypted-embed\",\"endpoint_url\":\"http://embed\"}", true
                ),
                new DifyProviderModelMapper.ProviderModelRow(
                        "llm-model", "tenant-a", "openai", "gpt-4o-mini", "llm",
                        "{\"api_key\":\"encrypted-llm\",\"endpoint_url\":\"http://llm\"}", true
                ),
                new DifyProviderModelMapper.ProviderModelRow(
                        "rerank-model", "tenant-a", "jina", "jina-reranker-v2", "reranking",
                        "{\"api_key\":\"encrypted-rerank\",\"base_url\":\"http://rerank\"}", true
                )
        ));
        FakeTagBindingMapper tagBindingMapper = new FakeTagBindingMapper(List.of());
        DifyMetadataSource source = new DifyMetadataSource(
                datasetMapper,
                documentMapper,
                providerModelMapper,
                tagBindingMapper,
                properties
        );

        MetadataPayload payload = source.load();

        assertEquals("plain-default-token", payload.modelsById().get("embed-model").apiKey());
        assertEquals("plain-default-token", payload.modelsById().get("llm-model").apiKey());
        assertEquals("plain-default-token", payload.modelsById().get("rerank-model").apiKey());
    }

    private static final class FakeDatasetMapper implements DifyDatasetMapper {
        private final List<DatasetRow> rows;

        private FakeDatasetMapper(List<DatasetRow> rows) {
            this.rows = rows;
        }

        @Override
        public List<DatasetRow> scanAllDatasets() {
            return rows;
        }
    }

    private static final class FakeDocumentMapper implements DifyDocumentMapper {
        private final List<DocumentRow> rows;

        private FakeDocumentMapper(List<DocumentRow> rows) {
            this.rows = rows;
        }

        @Override
        public List<DocumentRow> scanAllDocuments() {
            return rows;
        }
    }

    private static final class FakeProviderModelMapper implements DifyProviderModelMapper {
        private final List<ProviderModelRow> rows;
        private String requestedTenantId;

        private FakeProviderModelMapper(List<ProviderModelRow> rows) {
            this.rows = rows;
        }

        @Override
        public List<ProviderModelRow> scanAllProviderModels(String tenantId) {
            this.requestedTenantId = tenantId;
            return rows;
        }
    }

    private static final class FakeTagBindingMapper implements DifyTagBindingMapper {
        private final List<TagBindingRow> rows;
        private List<String> requestedTagCodes;

        private FakeTagBindingMapper(List<TagBindingRow> rows) {
            this.rows = rows;
        }

        @Override
        public List<TagBindingRow> scanKnowledgeTagBindingsByTagCodes(List<String> tagCodes) {
            this.requestedTagCodes = tagCodes;
            return rows;
        }
    }
}
