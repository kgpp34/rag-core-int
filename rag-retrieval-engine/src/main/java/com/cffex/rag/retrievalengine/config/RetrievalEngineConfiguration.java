package com.cffex.rag.retrievalengine.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import com.cffex.rag.retrievalengine.domain.service.CandidateFusionDomainService;
import com.cffex.rag.retrievalengine.domain.service.GlobalRankingDomainService;
import com.cffex.rag.retrievalengine.domain.service.KnowledgeBaseRankingDomainService;
import com.cffex.rag.retrievalengine.domain.service.KnowledgeBaseRecallDomainService;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

/** retrieval-engine 基础装配：负责 Milvus SDK 客户端实例化。 */
@Configuration
@EnableConfigurationProperties({
        MilvusProperties.class,
        ChatProperties.class,
        RetrievalDebugTraceProperties.class,
        RetrievalHttpClientProperties.class
})
public class RetrievalEngineConfiguration {

    @Bean
    @Lazy
    public MilvusClientV2 milvusClient(MilvusProperties properties) {
        // dense/sparse 两条检索路由都直接复用同一个 v2 客户端。
        ConnectConfig config = ConnectConfig.builder()
                .uri(properties.uri())
                .username(properties.username())
                .password(properties.password())
                .dbName(properties.databaseName())
                .build();
        return new MilvusClientV2(config);
    }

    @Bean
    public CandidateFusionDomainService candidateFusionDomainService() {
        return new CandidateFusionDomainService();
    }

    @Bean
    public KnowledgeBaseRankingDomainService knowledgeBaseRankingDomainService() {
        return new KnowledgeBaseRankingDomainService();
    }

    @Bean
    public KnowledgeBaseRecallDomainService knowledgeBaseRecallDomainService(
            CandidateFusionDomainService candidateFusionDomainService
    ) {
        return new KnowledgeBaseRecallDomainService(candidateFusionDomainService);
    }

    @Bean
    public GlobalRankingDomainService globalRankingDomainService() {
        return new GlobalRankingDomainService();
    }
}
