package com.cffex.rag.retrievalengine.infrastructure.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.cffex.rag.retrievalengine.domain.model.EmbeddingModelPolicy;

class OpenAiCompatibleQueryEmbeddingAdapterTest {

    @Test
    void embed_sendsDifyQueryInputTypeAndNormalizesVector() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleQueryEmbeddingAdapter adapter = new OpenAiCompatibleQueryEmbeddingAdapter(builder.build());

        server.expect(requestTo("https://embedding.example.com/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer plain-token"))
                .andExpect(jsonPath("$.model").value("bge-m3"))
                .andExpect(jsonPath("$.input").value("hello"))
                .andExpect(jsonPath("$.input_type").value("query"))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            {
                              "embedding": [3.0, 4.0]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        float[] vector = adapter.embed(
                "hello",
                new EmbeddingModelPolicy("https://embedding.example.com/v1", "plain-token", "bge-m3")
        );

        assertThat(vector).containsExactly(0.6f, 0.8f);
        server.verify();
    }

    @Test
    void resolveEmbeddingsRequestUrl_handlesBaseAndFullEmbeddingEndpoints() {
        assertThat(OpenAiCompatibleQueryEmbeddingAdapter.resolveEmbeddingsRequestUrl("https://api.example.com"))
                .isEqualTo("https://api.example.com/v1/embeddings");
        assertThat(OpenAiCompatibleQueryEmbeddingAdapter.resolveEmbeddingsRequestUrl("https://api.example.com/v1"))
                .isEqualTo("https://api.example.com/v1/embeddings");
        assertThat(OpenAiCompatibleQueryEmbeddingAdapter.resolveEmbeddingsRequestUrl("https://api.example.com/v1/embeddings"))
                .isEqualTo("https://api.example.com/v1/embeddings");
    }
}
