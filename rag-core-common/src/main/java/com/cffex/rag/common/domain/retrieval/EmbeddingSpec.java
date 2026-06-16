package com.cffex.rag.common.domain.retrieval;

import java.util.Objects;

/**
 * 已解析的 embedding 模型连接规格。
 *
 * <p>由 query-planner 在查询元数据后填充，retrieval-engine 直接使用，不再查询 MetadataQueryService。
 */
public record EmbeddingSpec(ModelEndpointSpec modelEndpoint) {
    public EmbeddingSpec {
        modelEndpoint = Objects.requireNonNull(modelEndpoint, "modelEndpoint must not be null");
    }
}
