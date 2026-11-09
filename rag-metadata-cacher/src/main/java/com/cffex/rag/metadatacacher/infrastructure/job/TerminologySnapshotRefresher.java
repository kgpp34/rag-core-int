package com.cffex.rag.metadatacacher.infrastructure.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cffex.rag.metadatacacher.application.DefaultTerminologyService;
import com.cffex.rag.metadatacacher.infrastructure.persistence.JdbcTerminologyStore;

/**
 * 术语快照预热与定时刷新：启动即全量预热，之后按固定间隔刷新。
 *
 * <p>fail-soft：加载失败仅告警并保留上一版快照，检索永不因术语数据问题失败。
 */
@Component
@ConditionalOnProperty(name = "rag.terminology-replacement.enabled", havingValue = "true")
public class TerminologySnapshotRefresher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TerminologySnapshotRefresher.class);

    private final JdbcTerminologyStore terminologyStore;
    private final DefaultTerminologyService terminologyService;

    public TerminologySnapshotRefresher(
            JdbcTerminologyStore terminologyStore,
            DefaultTerminologyService terminologyService
    ) {
        this.terminologyStore = terminologyStore;
        this.terminologyService = terminologyService;
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    @Scheduled(
            initialDelayString = "${rag.terminology-replacement.initial-delay-ms:300000}",
            fixedDelayString = "${rag.terminology-replacement.refresh-interval-ms:300000}"
    )
    public void refresh() {
        try {
            terminologyService.reload(terminologyStore.loadAll());
        } catch (RuntimeException ex) {
            terminologyService.recordRefreshFailure(ex);
            log.warn("术语快照刷新失败，保留上一版本快照", ex);
        }
    }
}
