package com.cffex.rag.common.domain.retrieval;

import java.util.Map;
import java.util.Objects;

/**
 * 规划后可直接执行的检索能力绑定。
 */
public record RetrievalBinding(
        RetrievalCapability capability,
        String engineId,
        String targetName,
        Map<String, Object> options
) {
    public RetrievalBinding {
        capability = Objects.requireNonNull(capability, "capability must not be null");
        engineId = Objects.requireNonNull(engineId, "engineId must not be null");
        targetName = Objects.requireNonNull(targetName, "targetName must not be null");
        options = Map.copyOf(options == null ? Map.of() : options);
    }

    public RetrievalBinding(
            RetrievalCapability capability,
            String engineId,
            String targetName
    ) {
        this(capability, engineId, targetName, Map.of());
    }
}
