package com.cffex.rag.common.domain.metadata;

import java.util.Map;
import java.util.Objects;

import com.cffex.rag.common.domain.retrieval.RetrievalCapability;

/**
 * 知识库元数据中声明的检索能力绑定。
 */
public record RetrievalBindingMeta(
        RetrievalCapability capability,
        String engineId,
        String targetName,
        Map<String, Object> options,
        boolean enabled
) {
    public RetrievalBindingMeta {
        capability = Objects.requireNonNull(capability, "capability must not be null");
        engineId = Objects.requireNonNull(engineId, "engineId must not be null");
        targetName = Objects.requireNonNull(targetName, "targetName must not be null");
        options = Map.copyOf(options == null ? Map.of() : options);
    }

    public RetrievalBindingMeta(
            RetrievalCapability capability,
            String engineId,
            String targetName,
            boolean enabled
    ) {
        this(capability, engineId, targetName, Map.of(), enabled);
    }
}
