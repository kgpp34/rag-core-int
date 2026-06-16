package com.cffex.rag.metadatacacher.domain;

import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.BusinessDomain;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单个元数据源加载完成后产出的不可变载荷。
 *
 * <p>调用方应传入不可变 Map；这里不再重复做防御性拷贝，
 * 以减少热路径上的额外分配开销。
 */
public record MetadataPayload(
        Map<String, KnowledgeBaseMeta> knowledgeBasesById,
        Map<String, ModelMeta> modelsById,
        Map<String, String> documentToKnowledgeBase,
        Map<String, DocumentMeta> documentMetasById,
        Map<BusinessDomain, List<String>> domainToKnowledgeBases
) {
    public MetadataPayload {
        Objects.requireNonNull(knowledgeBasesById, "knowledgeBasesById must not be null");
        Objects.requireNonNull(modelsById, "modelsById must not be null");
        Objects.requireNonNull(documentToKnowledgeBase, "documentToKnowledgeBase must not be null");
        Objects.requireNonNull(documentMetasById, "documentMetasById must not be null");
        Objects.requireNonNull(domainToKnowledgeBases, "domainToKnowledgeBases must not be null");
    }

    public static MetadataPayload empty() {
        return new MetadataPayload(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}
