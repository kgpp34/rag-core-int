package com.cffex.rag.retrievalengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "retrieval.debug-trace")
public record RetrievalDebugTraceProperties(
        boolean enabled,
        String outputDir,
        int textPreviewLength,
        boolean includeEmbeddingVector
) {
    public RetrievalDebugTraceProperties {
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "/tmp/retrieval-traces/rag-core-int";
        }
        if (textPreviewLength <= 0) {
            textPreviewLength = 200;
        }
    }
}
