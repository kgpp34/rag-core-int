package com.cffex.rag.app.config;

import java.util.List;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI ragCoreOpenApi() {
        return new OpenAPI().info(new Info()
                .title("RAG Core API")
                .version("v1")
                .description("RAG 检索与问答接口文档。")
                .contact(new Contact().name("rag-core-int")))
                .servers(List.of(new Server()
                        .url("/")
                        .description("Current deployment")));
    }

    @Bean
    public GroupedOpenApi ragApiGroup() {
        return GroupedOpenApi.builder()
                .group("rag")
                .packagesToScan("com.cffex.rag.app.web")
                .pathsToMatch("/api/v1/**")
                .build();
    }
}
