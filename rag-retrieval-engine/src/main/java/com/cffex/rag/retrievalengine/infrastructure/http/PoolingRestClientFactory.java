package com.cffex.rag.retrievalengine.infrastructure.http;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.pool.PoolStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.cffex.rag.retrievalengine.config.RetrievalHttpClientProperties;

public final class PoolingRestClientFactory {

    private static final Logger log = LoggerFactory.getLogger(PoolingRestClientFactory.class);

    private PoolingRestClientFactory() {
    }

    public static RestClient create(RestClient.Builder builder, RetrievalHttpClientProperties properties) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(properties.maxConnections())
                .setMaxConnPerRoute(properties.maxConnectionsPerRoute())
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();

        return builder.clone()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor((request, body, execution) -> {
                    long startNanos = System.nanoTime();
                    PoolStats before = connectionManager.getTotalStats();
                    String callId = request.getHeaders().getFirst("X-Rag-Rerank-Call-Id");
                    log.info(
                            "RAG_HTTP_START | callId={} method={} uri={} bodyBytes={} poolLeased={} poolPending={} poolAvailable={} poolMax={} thread={}",
                            callId,
                            request.getMethod(),
                            request.getURI(),
                            body.length,
                            before.getLeased(),
                            before.getPending(),
                            before.getAvailable(),
                            before.getMax(),
                            Thread.currentThread().getName()
                    );
                    try {
                        var response = execution.execute(request, body);
                        PoolStats after = connectionManager.getTotalStats();
                        log.info(
                                "RAG_HTTP_END | callId={} method={} uri={} status={} elapsedMs={} poolLeased={} poolPending={} poolAvailable={} poolMax={} thread={}",
                                callId,
                                request.getMethod(),
                                request.getURI(),
                                response.getStatusCode().value(),
                                elapsedMs(startNanos),
                                after.getLeased(),
                                after.getPending(),
                                after.getAvailable(),
                                after.getMax(),
                                Thread.currentThread().getName()
                        );
                        return response;
                    } catch (Exception ex) {
                        PoolStats after = connectionManager.getTotalStats();
                        log.warn(
                                "RAG_HTTP_ERROR | callId={} method={} uri={} elapsedMs={} poolLeased={} poolPending={} poolAvailable={} poolMax={} error={} thread={}",
                                callId,
                                request.getMethod(),
                                request.getURI(),
                                elapsedMs(startNanos),
                                after.getLeased(),
                                after.getPending(),
                                after.getAvailable(),
                                after.getMax(),
                                ex.toString(),
                                Thread.currentThread().getName()
                        );
                        throw ex;
                    }
                })
                .build();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

}
