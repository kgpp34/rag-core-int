package com.cffex.rag.metadatacacher.infrastructure.job;

import com.cffex.rag.metadatacacher.application.MetadataCacheRefreshService;
import com.cffex.rag.metadatacacher.domain.port.MetadataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 元数据定时同步任务。
 *
 * <p>按配置的固定间隔触发 Dify 元数据刷新，适合作为后台增量同步入口。
 */
@Component
@ConditionalOnProperty(
        name = "metadata-cacher.scheduler.dify-db.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MetadataRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(MetadataRefreshScheduler.class);

    private final MetadataCacheRefreshService metadataCacheRefreshService;
    private final MetadataSource difySource;

    public MetadataRefreshScheduler(
            MetadataCacheRefreshService metadataCacheRefreshService,
            @Qualifier("difySource") MetadataSource difySource
    ) {
        this.metadataCacheRefreshService = metadataCacheRefreshService;
        this.difySource = difySource;
    }

    @Scheduled(
            initialDelayString = "${metadata-cacher.scheduler.dify-db.initial-delay-ms:5000}",
            fixedDelayString = "${metadata-cacher.scheduler.dify-db.fixed-delay-ms:300000}"
    )
    public void syncFromDify() {
        log.info("定时任务触发元数据同步，数据源={}", difySource.sourceName());
        metadataCacheRefreshService.refresh(difySource);
    }
}
