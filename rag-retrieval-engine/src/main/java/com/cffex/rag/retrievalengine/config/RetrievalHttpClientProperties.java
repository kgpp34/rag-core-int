package com.cffex.rag.retrievalengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "retrieval.http-client")
public record RetrievalHttpClientProperties(
        int maxConnections,
        int maxConnectionsPerRoute
) {

    public RetrievalHttpClientProperties {
        if (maxConnections <= 0) {
            maxConnections = 64;
        }
        if (maxConnectionsPerRoute <= 0) {
            maxConnectionsPerRoute = Math.min(32, maxConnections);
        }
    }
}
