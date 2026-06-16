package com.cffex.rag.retrievalengine.infrastructure.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

import com.cffex.rag.retrievalengine.domain.model.EmbeddingModelPolicy;

class OpenAiCompatibleEmbeddingModelFactoryTest {

    @Test
    void getEmbeddingModel_usesPolicyModelAsDefaultOpenAiEmbeddingModel() throws Exception {
        OpenAiCompatibleEmbeddingModelFactory factory = new OpenAiCompatibleEmbeddingModelFactory();
        EmbeddingModelPolicy policy = new EmbeddingModelPolicy(
                "https://embedding.example.com/v1",
                "plain-token",
                "bge-m3"
        );

        OpenAiEmbeddingModel model = (OpenAiEmbeddingModel) factory.getEmbeddingModel(policy);
        Field defaultOptionsField = OpenAiEmbeddingModel.class.getDeclaredField("defaultOptions");
        defaultOptionsField.setAccessible(true);

        OpenAiEmbeddingOptions options = (OpenAiEmbeddingOptions) defaultOptionsField.get(model);

        assertThat(options.getModel()).isEqualTo("bge-m3");
    }
}
