package com.cffex.rag.metadatacacher.application;

import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.MetadataSnapshot;
import com.cffex.rag.metadatacacher.domain.MetadataPayload;
import com.cffex.rag.metadatacacher.domain.port.MetadataSnapshotStore;
import com.cffex.rag.metadatacacher.domain.port.MetadataSource;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 元数据缓存刷新服务。
 *
 * <p>负责从指定数据源拉取完整元数据快照，并以新版本覆盖当前缓存。
 * 刷新过程中的开始、完成和失败都会记录日志，便于排查同步链路问题。
 */
@Service
public class MetadataCacheRefreshService {

    private static final Logger log = LoggerFactory.getLogger(MetadataCacheRefreshService.class);

    private final MetadataSnapshotStore snapshotStore;
    private final AtomicLong versionSequence = new AtomicLong();

    public MetadataCacheRefreshService(MetadataSnapshotStore snapshotStore) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
    }

    public MetadataSnapshot refresh(MetadataSource source) {
        long start = System.currentTimeMillis();
        log.info("开始刷新元数据，数据源={}", source.sourceName());
        try {
            MetadataPayload incoming = source.load();
            MetadataSnapshot updated = new MetadataSnapshot(
                    versionSequence.incrementAndGet(),
                    Instant.now(),
                    incoming.knowledgeBasesById(),
                    incoming.modelsById(),
                    incoming.documentToKnowledgeBase(),
                    incoming.documentMetasById(),
                    incoming.domainToKnowledgeBases()
            );
            snapshotStore.put(updated);
            log.info(
                    "元数据刷新完成，数据源={}，版本={}，知识库数={}，模型数={}，文档数={}，耗时={}ms",
                    source.sourceName(),
                    updated.version(),
                    updated.knowledgeBasesById().size(),
                    updated.modelsById().size(),
                    updated.documentMetasById().size(),
                    System.currentTimeMillis() - start
            );
            log.info(
                    "已加载模型清单，数据源={}，版本={}，模型详情={}",
                    source.sourceName(),
                    updated.version(),
                    formatModels(updated)
            );
            return updated;
        } catch (RuntimeException ex) {
            log.error("元数据刷新失败，数据源={}，耗时={}ms",
                    source.sourceName(),
                    System.currentTimeMillis() - start,
                    ex);
            throw ex;
        }
    }

    /**
     * 输出当前快照中的模型摘要，方便直接通过日志核对默认 tenant 下实际加载到的模型。
     */
    private String formatModels(MetadataSnapshot snapshot) {
        if (snapshot.modelsById().isEmpty()) {
            return "[]";
        }
        return snapshot.modelsById().values().stream()
                .sorted(Comparator.comparing(ModelMeta::modelId))
                .map(model -> "{id=" + model.modelId()
                        + ", name=" + model.modelName()
                        + ", type=" + model.modelType()
                        + ", enabled=" + model.enabled()
                        + "}")
                .reduce((left, right) -> left + ", " + right)
                .map(joined -> "[" + joined + "]")
                .orElse("[]");
    }
}
