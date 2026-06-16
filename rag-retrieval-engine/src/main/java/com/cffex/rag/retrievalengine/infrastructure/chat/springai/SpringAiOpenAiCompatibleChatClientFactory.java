package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;

import reactor.netty.http.client.HttpClient;

@Component
public class SpringAiOpenAiCompatibleChatClientFactory implements ChatClientFactory {

    private final RestClient.Builder restClientBuilder;

    private static final HttpComponentsClientHttpRequestFactory HTTP1_FACTORY;

    private static final WebClient.Builder HTTP1_WEB_CLIENT_BUILDER;

    static {
        TlsConfig tlsConfig = TlsConfig.custom()
            .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
            .build();

        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setMaxConnTotal(200)
                    .setMaxConnPerRoute(50)
                    .setDefaultTlsConfig(tlsConfig)
                    .build()
            )
            .build();

        HTTP1_FACTORY = new HttpComponentsClientHttpRequestFactory(httpClient);

        HttpClient reactorHttpClient = HttpClient.create()
            .protocol(reactor.netty.http.HttpProtocol.HTTP11);
        HTTP1_WEB_CLIENT_BUILDER = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(reactorHttpClient));
    }

    public SpringAiOpenAiCompatibleChatClientFactory(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = Objects.requireNonNull(restClientBuilder);
    }

    @Override
    public ChatClient create(ChatCompletionRequest request) {
        EndpointResolution endpointResolution = resolveEndpoint(request.modelPolicy().endpoint());
        OpenAiApi openAiApi = OpenAiApi.builder()
            .baseUrl(endpointResolution.baseUrl())
            .completionsPath(endpointResolution.completionsPath())
            .apiKey((ApiKey) () -> request.modelPolicy().authToken())
            .restClientBuilder(restClientBuilder.clone()
                .requestFactory(HTTP1_FACTORY))
            .webClientBuilder(HTTP1_WEB_CLIENT_BUILDER)
            .build();
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
            .model(request.modelPolicy().model());
        if (request.temperature() != null) {
            optionsBuilder.temperature(request.temperature());
        }
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            optionsBuilder.maxTokens(request.maxTokens());
        }
        OpenAiChatOptions options = optionsBuilder.build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(options)
            .build();
        return ChatClient.builder(chatModel)
            .defaultOptions(options)
            .build();
    }

    static EndpointResolution resolveEndpoint(String endpoint) {
        String normalizedEndpoint = trimTrailingSlash(endpoint);
        URI uri = URI.create(normalizedEndpoint);
        String path = uri.getPath();
        String resolvedBasePath = path == null || path.isBlank() ? "" : path;
        if (resolvedBasePath.endsWith("/chat/completions")) {
            resolvedBasePath = resolvedBasePath.substring(0, resolvedBasePath.length() - "/chat/completions".length());
        }
        String baseUrl = rebuildUri(uri, resolvedBasePath);
        return new EndpointResolution(baseUrl, "/chat/completions");
    }

    private static String trimTrailingSlash(String endpoint) {
        String normalized = endpoint == null ? "" : endpoint.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String rebuildUri(URI uri, String path) {
        try {
            return new URI(
                uri.getScheme(),
                uri.getAuthority(),
                path == null || path.isBlank() ? null : path,
                uri.getQuery(),
                uri.getFragment()
            ).toString();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid LLM endpoint: " + uri, ex);
        }
    }

    record EndpointResolution(String baseUrl, String completionsPath) {
    }
}
