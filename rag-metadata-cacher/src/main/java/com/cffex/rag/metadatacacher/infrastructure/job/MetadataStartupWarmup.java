package com.cffex.rag.metadatacacher.infrastructure.job;

import com.cffex.rag.common.domain.metadata.MetadataSnapshot;
import com.cffex.rag.metadatacacher.application.MetadataCacheRefreshService;
import com.cffex.rag.metadatacacher.config.MetadataCacherProperties;
import com.cffex.rag.metadatacacher.domain.port.MetadataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 应用启动预热任务。
 *
 * <p>在服务启动后立即执行一次元数据同步，确保首个请求到来前缓存已准备完成。
 */
@Component
@ConditionalOnProperty(name = "metadata-cacher.warmup-enabled", havingValue = "true", matchIfMissing = true)
public class MetadataStartupWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MetadataStartupWarmup.class);

    private final MetadataCacheRefreshService metadataCacheRefreshService;
    private final MetadataCacherProperties metadataCacherProperties;
    private final MetadataSource difySource;

    public MetadataStartupWarmup(
            MetadataCacheRefreshService metadataCacheRefreshService,
            MetadataCacherProperties metadataCacherProperties,
            @Qualifier("difySource") MetadataSource difySource
    ) {
        this.metadataCacheRefreshService = metadataCacheRefreshService;
        this.metadataCacherProperties = metadataCacherProperties;
        this.difySource = difySource;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始执行元数据启动预热，数据源={}", difySource.sourceName());
        try {
            MetadataSnapshot snapshot = metadataCacheRefreshService.refresh(difySource);
            log.info(
                    "元数据启动预热完成，数据源={}，版本={}，知识库数={}，模型数={}，文档数={}",
                    difySource.sourceName(),
                    snapshot.version(),
                    snapshot.knowledgeBasesById().size(),
                    snapshot.modelsById().size(),
                    snapshot.documentToKnowledgeBase().size()
            );
        } catch (RuntimeException ex) {
            if (metadataCacherProperties.isWarmupFailFast()) {
                log.error("元数据启动预热失败且配置为 fail-fast，数据源={}", difySource.sourceName(), ex);
                throw ex;
            }
            log.warn("元数据启动预热失败，已按降级模式继续启动应用，数据源={}", difySource.sourceName(), ex);
        }
    }
}
