package com.cffex.rag.metadatacacher.domain.port;

import com.cffex.rag.common.domain.metadata.MetadataSnapshot;
import java.util.Optional;

/** 元数据快照存储出站端口。 */
public interface MetadataSnapshotStore {

    Optional<MetadataSnapshot> get();

    void put(MetadataSnapshot snapshot);
}
