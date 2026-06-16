package com.cffex.rag.app.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.cffex.rag.app.RagCoreApplication;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 在 Maven compile 阶段启动一次应用并导出 OpenAPI 文档。
 */
public final class OpenApiDocumentGenerator {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RETRIES = 20;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private OpenApiDocumentGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path output = resolveOutputPath();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RagCoreApplication.class)
                .profiles("openapi")
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "spring.main.lazy-initialization=true",
                        "spring.main.banner-mode=off",
                        "rag.chat.memory-enabled=false"
                )
                .run(args);

        try {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            String document = fetchOpenApiDocument(port);
            Files.createDirectories(output.getParent());
            Files.writeString(output, prettyPrintJson(document), StandardCharsets.UTF_8);
            System.out.println("Generated OpenAPI document at " + output.toAbsolutePath());
        } finally {
            context.close();
        }
    }

    private static Path resolveOutputPath() {
        String output = System.getProperty("openapi.output", "app/target/openapi/openapi.json");
        return Path.of(output);
    }

    private static String fetchOpenApiDocument(int port) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        URI uri = URI.create("http://127.0.0.1:" + port + "/v3/api-docs");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        IOException lastIoException = null;
        InterruptedException lastInterruptedException = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new IOException("OpenAPI endpoint returned status " + response.statusCode());
                }
                return response.body();
            } catch (IOException ex) {
                lastIoException = ex;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                lastInterruptedException = ex;
                break;
            }
            Thread.sleep(RETRY_DELAY);
        }

        if (lastInterruptedException != null) {
            throw lastInterruptedException;
        }
        throw lastIoException != null ? lastIoException : new IOException("Failed to fetch OpenAPI document");
    }

    private static String prettyPrintJson(String document) throws IOException {
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(OBJECT_MAPPER.readTree(document));
    }
}
