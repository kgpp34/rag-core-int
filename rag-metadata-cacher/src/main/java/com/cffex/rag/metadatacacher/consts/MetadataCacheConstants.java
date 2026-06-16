package com.cffex.rag.metadatacacher.consts;

import java.time.Instant;
import java.util.Map;
import com.cffex.rag.common.domain.metadata.MetadataSnapshot;

public final class MetadataCacheConstants {

    public static final String DIFY_SOURCE_NAME = "dify-db";
    public static final MetadataSnapshot EMPTY_SNAPSHOT =
            new MetadataSnapshot(0L, Instant.EPOCH, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    private MetadataCacheConstants() {
    }
}
