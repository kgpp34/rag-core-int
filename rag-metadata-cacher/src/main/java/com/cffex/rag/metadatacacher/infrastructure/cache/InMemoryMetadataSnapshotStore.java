package com.cffex.rag.metadatacacher.infrastructure.cache;

import com.cffex.rag.common.domain.metadata.MetadataSnapshot;
import com.cffex.rag.metadatacacher.domain.port.MetadataSnapshotStore;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于内存的元数据快照存储实现。
 *
 * <p>底层使用 {@link AtomicReference} 保存当前快照；
 * 读取无锁，写入时只执行一次原子替换。
 */
public class InMemoryMetadataSnapshotStore implements MetadataSnapshotStore {

    private final AtomicReference<MetadataSnapshot> ref = new AtomicReference<>();

    @Override
    public Optional<MetadataSnapshot> get() {
        return Optional.ofNullable(ref.get());
    }

    @Override
    public void put(MetadataSnapshot snapshot) {
        ref.set(snapshot);
    }
}
