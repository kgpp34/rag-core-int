package com.cffex.rag.metadatacacher.application;

import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition;
import com.cffex.rag.common.domain.metadata.MetadataSnapshot;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.ModelQueryCondition;
import com.cffex.rag.common.service.MetadataQueryService;
import com.cffex.rag.metadatacacher.consts.MetadataCacheConstants;
import com.cffex.rag.metadatacacher.domain.port.MetadataSnapshotStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MetadataCacheQueryService implements MetadataQueryService {

    private static final Logger log = LoggerFactory.getLogger(MetadataCacheQueryService.class);
    private static final int SAMPLE_SIZE = 5;

    private final MetadataSnapshotStore snapshotStore;

    public MetadataCacheQueryService(MetadataSnapshotStore snapshotStore) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
    }

    @Override
    public List<ModelMeta> listModels(ModelQueryCondition condition) {
        MetadataSnapshot snapshot = currentSnapshot();
        return snapshot.modelsById().values().stream()
                .filter(ModelMeta::enabled)
                .filter(model -> condition.modelType() == null || model.modelType() == condition.modelType())
                .filter(model -> matchModelName(model, condition.modelName()))
                .toList();
    }

    @Override
    public List<KnowledgeBaseMeta> listKnowledgeBases(KnowledgeBaseQueryCondition condition) {
        MetadataSnapshot snapshot = currentSnapshot();
        Set<String> filteredIds = new LinkedHashSet<>(snapshot.knowledgeBasesById().keySet());
        if (!condition.documentIds().isEmpty()) {
            Set<String> matchedKnowledgeBaseIds = resolveKnowledgeBaseIdsByDocumentIds(snapshot, condition.documentIds());
            filteredIds.retainAll(matchedKnowledgeBaseIds);
            if (filteredIds.isEmpty()) {
                log.warn(
                        "按文档过滤知识库结果为空，requestDocCount={}，cacheDocumentCount={}，cacheKnowledgeBaseCount={}，matchedKnowledgeBaseCount={}，sampleRequestDocIds={}，sampleCacheDocIds={}，sampleMatchedKnowledgeBaseIds={}",
                        condition.documentIds().size(),
                        snapshot.documentToKnowledgeBase().size(),
                        snapshot.knowledgeBasesById().size(),
                        matchedKnowledgeBaseIds.size(),
                        sample(condition.documentIds()),
                        sample(snapshot.documentToKnowledgeBase().keySet()),
                        sample(matchedKnowledgeBaseIds)
                );
            }
        }
        return filteredIds.stream()
                .map(snapshot.knowledgeBasesById()::get)
                .filter(Objects::nonNull)
                .filter(KnowledgeBaseMeta::enabled)
                .toList();
    }

    @Override
    public Map<String, DocumentMeta> getDocumentMetas(List<String> documentIds) {
        MetadataSnapshot snapshot = currentSnapshot();
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>(documentIds);
        Map<String, DocumentMeta> result = new java.util.LinkedHashMap<>();
        for (String documentId : uniqueIds) {
            DocumentMeta meta = snapshot.documentMetasById().get(documentId);
            if (meta != null) {
                result.put(documentId, meta);
            }
        }
        return Map.copyOf(result);
    }

    private MetadataSnapshot currentSnapshot() {
        return snapshotStore.get().orElse(MetadataCacheConstants.EMPTY_SNAPSHOT);
    }

    private boolean matchModelName(ModelMeta model, String modelName) {
        if (modelName == null) {
            return true;
        }
        return model.modelName().equalsIgnoreCase(modelName);
    }

    private Set<String> resolveKnowledgeBaseIdsByDocumentIds(MetadataSnapshot snapshot, List<String> documentIds) {
        return documentIds.stream()
                .map(snapshot.documentToKnowledgeBase()::get)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static <T> List<T> sample(Iterable<T> values) {
        java.util.ArrayList<T> result = new java.util.ArrayList<>(SAMPLE_SIZE);
        for (T value : values) {
            if (result.size() >= SAMPLE_SIZE) {
                break;
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

}
