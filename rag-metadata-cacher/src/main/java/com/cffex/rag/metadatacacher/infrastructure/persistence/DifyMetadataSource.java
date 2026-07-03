package com.cffex.rag.metadatacacher.infrastructure.persistence;

import com.cffex.rag.common.domain.metadata.BusinessDomain;
import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.metadatacacher.config.MetadataCacherProperties;
import com.cffex.rag.metadatacacher.consts.MetadataCacheConstants;
import com.cffex.rag.metadatacacher.domain.MetadataPayload;
import com.cffex.rag.metadatacacher.domain.port.MetadataSource;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyDatasetMapper;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyDocumentMapper;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyProviderModelMapper;
import com.cffex.rag.metadatacacher.infrastructure.persistence.mapper.DifyTagBindingMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 Dify 主库的元数据加载器。
 *
 * <p>负责从数据库读取知识库、文档、模型与标签绑定信息，
 * 并据此组装业务域路由所需的完整元数据载荷。
 */
@Component("difySource")
public class DifyMetadataSource implements MetadataSource {

    private static final Logger log = LoggerFactory.getLogger(DifyMetadataSource.class);

    private final DifyDatasetMapper datasetMapper;
    private final DifyDocumentMapper documentMapper;
    private final DifyProviderModelMapper providerModelMapper;
    private final DifyTagBindingMapper tagBindingMapper;
    private final MetadataCacherProperties properties;

    public DifyMetadataSource(
            DifyDatasetMapper datasetMapper,
            DifyDocumentMapper documentMapper,
            DifyProviderModelMapper providerModelMapper,
            DifyTagBindingMapper tagBindingMapper,
            MetadataCacherProperties properties
    ) {
        this.datasetMapper = datasetMapper;
        this.documentMapper = documentMapper;
        this.providerModelMapper = providerModelMapper;
        this.tagBindingMapper = tagBindingMapper;
        this.properties = properties;
    }

    @Override
    public String sourceName() {
        return MetadataCacheConstants.DIFY_SOURCE_NAME;
    }

    @Override
    public MetadataPayload load() {
        log.info(
                "开始从 Dify 数据库加载元数据，defaultTenantId={}，启用统一默认API密钥={}",
                properties.getDify().getDefaultTenantId(),
                properties.getDify().getDefaultApiKey() != null && !properties.getDify().getDefaultApiKey().isBlank()
        );
        // 读取知识库元数据
        Map<String, KnowledgeBaseMeta> knowledgeBasesById = readAsMap(
                datasetMapper.scanAllDatasets(),
                DifyDatasetMapper.DatasetRow::datasetId,
                row -> new KnowledgeBaseMeta(
                        row.datasetId(),
                        MetadataConverter.toRetrievalMode(row.retrievalMode()),
                        row.collectionName(),
                        row.enabled(),
                        row.topK(),
                        row.rerankingEnabled(),
                        row.scoreThresholdEnabled(),
                        row.scoreThreshold(),
                        row.vectorWeight(),
                        row.keywordWeight(),
                        row.datasetName()
                )
        );
        // 读取文档元数据，并保留文档到知识库的映射关系
        Map<String, DocumentMeta> documentMetasById = readAsMap(
                documentMapper.scanAllDocuments(),
                DifyDocumentMapper.DocumentRow::documentId,
                row -> new DocumentMeta(
                        row.documentId(),
                        row.datasetId(),
                        row.name(),
                        MetadataConverter.extractUploadFileId(row.dataSourceInfo()),
                        row.uploadFileKey()
                )
        );
        Map<String, String> documentToKnowledgeBase = documentMetasById.values().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        DocumentMeta::documentId,
                        DocumentMeta::knowledgeBaseId
                ));
        // 读取模型元数据
        Map<String, ModelMeta> modelsById = readAsMap(
                providerModelMapper.scanAllProviderModels(properties.getDify().getDefaultTenantId()),
                DifyProviderModelMapper.ProviderModelRow::modelId,
                row -> DifyProviderModelConfigParser.toModelMeta(row, properties.getDify().getDefaultApiKey())
        );
        Map<BusinessDomain, List<String>> domainToKnowledgeBases = buildDomainMappings(knowledgeBasesById);

        return new MetadataPayload(
                knowledgeBasesById,
                modelsById,
                documentToKnowledgeBase,
                documentMetasById,
                domainToKnowledgeBases
        );
    }

    private Map<BusinessDomain, List<String>> buildDomainMappings(Map<String, KnowledgeBaseMeta> knowledgeBasesById) {
        Map<String, BusinessDomain> tagCodeToDomain = buildTagCodeToDomain();
        Map<BusinessDomain, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        LinkedHashSet<String> matchedDatasetIds = new LinkedHashSet<>();
        List<String> configuredTagCodes = new ArrayList<>(tagCodeToDomain.keySet());
        if (!configuredTagCodes.isEmpty()) {
            for (DifyTagBindingMapper.TagBindingRow row :
                    tagBindingMapper.scanKnowledgeTagBindingsByTagCodes(configuredTagCodes)) {
                if (!knowledgeBasesById.containsKey(row.datasetId())) {
                    continue;
                }
                BusinessDomain domain = tagCodeToDomain.get(row.tagCode());
                if (domain == null) {
                    continue;
                }
                grouped.computeIfAbsent(domain, ignored -> new LinkedHashSet<>()).add(row.datasetId());
                matchedDatasetIds.add(row.datasetId());
            }
        }
        knowledgeBasesById.keySet().stream()
                .filter(datasetId -> !matchedDatasetIds.contains(datasetId))
                .forEach(datasetId -> grouped.computeIfAbsent(BusinessDomain.UNKNOWN, ignored -> new LinkedHashSet<>())
                        .add(datasetId));
        Map<BusinessDomain, List<String>> result = new LinkedHashMap<>();
        grouped.forEach((domain, datasetIds) -> result.put(domain, List.copyOf(datasetIds)));
        return Map.copyOf(result);
    }

    private Map<String, BusinessDomain> buildTagCodeToDomain() {
        Map<String, BusinessDomain> mapping = new LinkedHashMap<>();
        for (MetadataCacherProperties.DomainMapping domainMapping : properties.getDify().getDomainMappings()) {
            if (domainMapping.getDomainCode() == null || domainMapping.getDomainCode().isBlank()) {
                continue;
            }
            BusinessDomain domain = BusinessDomain.fromCode(domainMapping.getDomainCode());
            for (String tagCode : domainMapping.getTagCodes()) {
                String normalizedTagCode = normalize(tagCode);
                if (normalizedTagCode != null) {
                    mapping.put(normalizedTagCode, domain);
                }
            }
        }
        return Map.copyOf(mapping);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static <T, K, V> Map<K, V> readAsMap(
            Iterable<T> rows,
            Function<T, K> keyMapper,
            Function<T, V> valueMapper
    ) {
        Map<K, V> result = new LinkedHashMap<>();
        for (T row : rows) {
            result.put(keyMapper.apply(row), valueMapper.apply(row));
        }
        return Map.copyOf(result);
    }
}
