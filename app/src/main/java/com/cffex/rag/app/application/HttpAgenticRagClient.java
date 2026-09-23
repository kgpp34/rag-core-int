package com.cffex.rag.app.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.cffex.rag.app.config.AppProperties;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** HTTP/SSE adapter. The frontend never sees this internal protocol. */
@Component
final class HttpAgenticRagClient implements AgenticRagClient {

    private final AppProperties.Agentic properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    HttpAgenticRagClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.properties = appProperties.getRag().getAgentic();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public UUID createRun(List<AgenticMessage> messages, List<String> docIds, String requestId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messages", messages.stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList());
        payload.put("doc_ids", docIds == null ? List.of() : List.copyOf(docIds));
        if (requestId != null && !requestId.isBlank()) {
            payload.put("idempotency_key", idempotencyKey(requestId, messages, docIds));
        }
        JsonNode response = sendJson(
                "POST",
                "/v1/runs",
                payload,
                requestId,
                properties.getRunTimeout()
        );
        String id = text(response, "id");
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new RagServiceException(RagErrorCode.AGENTIC_PROTOCOL_ERROR,
                    "Agentic 服务返回了非法 run id", ex);
        }
    }

    @Override
    public void streamEvents(UUID runId, Consumer<AgenticEvent> eventConsumer, String requestId) {
        AtomicReference<String> lastEventId = new AtomicReference<>();
        for (int attempt = 0; attempt <= properties.getSseMaxReconnects(); attempt++) {
            HttpRequest.Builder requestBuilder = builder("GET", "/v1/runs/" + runId + "/events", requestId)
                    .header("Accept", "text/event-stream")
                    .timeout(properties.getRunTimeout());
            if (lastEventId.get() != null) {
                requestBuilder.header("Last-Event-ID", lastEventId.get());
            }
            try {
                HttpResponse<InputStream> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                ensureSuccess(response.statusCode(), response.body(), RagErrorCode.AGENTIC_UNAVAILABLE,
                        "连接 Agentic SSE 失败");
                try (InputStream body = response.body()) {
                    readSse(body, eventConsumer, lastEventId);
                }
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RagServiceException(RagErrorCode.AGENTIC_TIMEOUT, "Agentic SSE 请求被中断", ex);
            } catch (IOException ex) {
                if (attempt == properties.getSseMaxReconnects()) {
                    throw new RagServiceException(RagErrorCode.AGENTIC_UNAVAILABLE, "Agentic SSE 连接失败", ex);
                }
            }
        }
    }

    @Override
    public AgenticRunResult getRun(UUID runId, String requestId) {
        JsonNode response = sendJson("GET", "/v1/runs/" + runId, null, requestId, properties.getRunTimeout());
        return new AgenticRunResult(
                text(response, "status"),
                response.get("output"),
                response.get("error")
        );
    }

    @Override
    public void cancelRun(UUID runId, String requestId) {
        try {
            sendJson("POST", "/v1/runs/" + runId + "/cancel", null, requestId, properties.getConnectTimeout());
        } catch (RagServiceException ex) {
            // Cancellation is best effort after the client has already disconnected.
        }
    }

    private JsonNode sendJson(
            String method,
            String path,
            Object payload,
            String requestId,
            Duration timeout
    ) {
        HttpRequest.Builder builder = builder(method, path, requestId)
                .header("Accept", "application/json")
                .timeout(timeout);
        if (payload != null) {
            try {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            } catch (IOException ex) {
                throw new RagServiceException(RagErrorCode.AGENTIC_PROTOCOL_ERROR, "无法编码 Agentic 请求", ex);
            }
        } else if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RagServiceException(RagErrorCode.AGENTIC_RUN_FAILED,
                        "Agentic 服务返回 HTTP " + response.statusCode() + ": " + preview(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RagServiceException(RagErrorCode.AGENTIC_TIMEOUT, "Agentic 请求被中断", ex);
        } catch (IOException ex) {
            throw new RagServiceException(RagErrorCode.AGENTIC_UNAVAILABLE, "Agentic HTTP 请求失败", ex);
        }
    }

    private HttpRequest.Builder builder(String method, String path, String requestId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + path));
        if (requestId != null && !requestId.isBlank()) {
            builder.header("X-Request-ID", requestId);
            builder.header("X-Trace-Id", requestId);
        }
        if (properties.getAuthToken() != null) {
            builder.header("Authorization", "Bearer " + properties.getAuthToken());
        }
        return builder;
    }

    private void readSse(
            InputStream body,
            Consumer<AgenticEvent> consumer,
            AtomicReference<String> lastEventId
    ) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String id = null;
            String event = null;
            List<String> dataLines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (event != null && !dataLines.isEmpty()) {
                        JsonNode data;
                        try {
                            data = objectMapper.readTree(String.join("\n", dataLines));
                        } catch (IOException ex) {
                            throw new RagServiceException(RagErrorCode.AGENTIC_PROTOCOL_ERROR, "Agentic SSE 数据非法", ex);
                        }
                        consumer.accept(new AgenticEvent(id, event, data));
                        if (id != null && !id.isBlank()) {
                            lastEventId.set(id);
                        }
                    }
                    id = null;
                    event = null;
                    dataLines.clear();
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    dataLines.add(line.substring(5).stripLeading());
                }
            }
        }
    }

    private void ensureSuccess(int status, InputStream body, RagErrorCode code, String message) throws IOException {
        if (status < 200 || status >= 300) {
            body.close();
            throw new RagServiceException(code, message + ": HTTP " + status);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String preview(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500);
    }

    private static String idempotencyKey(String requestId, List<AgenticMessage> messages, List<String> docIds) {
        String requestPrefix = requestId.length() <= 63 ? requestId : requestId.substring(0, 63);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
        AgenticMessage currentMessage = messages.isEmpty() ? null : messages.getLast();
        String fingerprint = String.valueOf(currentMessage) + '\u001f' + (docIds == null ? List.of() : docIds).toString();
        String hash = HexFormat.of().formatHex(digest.digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
        return requestPrefix + '-' + hash;
    }
}
