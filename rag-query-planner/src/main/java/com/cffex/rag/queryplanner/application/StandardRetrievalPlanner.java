package com.cffex.rag.queryplanner.application;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseMeta;
import com.cffex.rag.common.domain.metadata.KnowledgeBaseQueryCondition;
import com.cffex.rag.common.domain.metadata.ModelMeta;
import com.cffex.rag.common.domain.metadata.ModelQueryCondition;
import com.cffex.rag.common.domain.metadata.ModelType;
import com.cffex.rag.common.domain.metadata.RetrievalMode;
import com.cffex.rag.common.domain.query.ExecutionPlan;
import com.cffex.rag.common.domain.query.PlanType;
import com.cffex.rag.common.domain.query.QueryPlanRequest;
import com.cffex.rag.common.domain.query.RetrievalPlan;
import com.cffex.rag.common.domain.retrieval.EmbeddingSpec;
import com.cffex.rag.common.domain.retrieval.KnowledgeBaseRecallSpec;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.common.domain.retrieval.RankingSpec;
import com.cffex.rag.common.domain.retrieval.RetrievalBinding;
import com.cffex.rag.common.domain.retrieval.RetrievalCapability;
import com.cffex.rag.common.domain.retrieval.RetrievalContext;
import com.cffex.rag.common.service.MetadataQueryService;
import com.cffex.rag.queryplanner.config.QueryPlannerProperties;

@Service
class StandardRetrievalPlanner implements PlannerStrategy {

    private static final Logger log = LoggerFactory.getLogger(StandardRetrievalPlanner.class);
    private static final double DISABLED_GLOBAL_SCORE_THRESHOLD = 0.0d;
    private static final int DIFY_DEFAULT_DATASET_TOP_K = 2;

    private final MetadataQueryService metadataQueryService;
    private final QueryPlannerProperties properties;

    StandardRetrievalPlanner(
            MetadataQueryService metadataQueryService,
            QueryPlannerProperties properties
    ) {
        this.metadataQueryService = Objects.requireNonNull(metadataQueryService);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public PlanType planType() {
        return PlanType.STANDARD_RETRIEVAL;
    }

    @Override
    public ExecutionPlan plan(QueryPlanRequest request) {
        if (request.query().isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }

        List<KnowledgeBaseMeta> knowledgeBases = resolveKnowledgeBases(request);
        ModelMeta embeddingModel = selectRequiredModel(ModelType.EMBEDDING, properties.getDefaults().getEmbeddingModelId());
        Optional<ModelMeta> rerankModel = selectOptionalModel(ModelType.RERANK, properties.getDefaults().getRerankModelId());

        RetrievalPlan retrievalPlan = buildPlan(
                request.query(),
                knowledgeBases,
                request.docIds(),
                embeddingModel,
                rerankModel,
                requirePositive(properties.getDefaults().getTopK(), "query-planner.defaults.top-k"),
                false,
                DISABLED_GLOBAL_SCORE_THRESHOLD
        );
        return new ExecutionPlan(
                PlanType.STANDARD_RETRIEVAL,
                request.query(),
                request.systemPrompt(),
                List.of(retrievalPlan),
                java.util.Map.of()
        );
    }

    @Override
    public ExecutionPlan plan(RetrievalContext context) {
        ModelMeta embeddingModel = selectRequiredModel(
                ModelType.EMBEDDING,
                firstNonBlank(context.embeddingModelId(), properties.getDefaults().getEmbeddingModelId())
        );
        Optional<ModelMeta> rerankModel = selectOptionalModel(
                ModelType.RERANK,
                firstNonBlank(context.rerankModelId(), properties.getDefaults().getRerankModelId())
        );

        RetrievalPlan retrievalPlan = buildPlan(
                context.query(),
                resolveKnowledgeBases(context.targetKnowledgeBaseIds()),
                context.targetDocIds(),
                embeddingModel,
                rerankModel,
                context.topK() > 0
                        ? context.topK()
                        : requirePositive(properties.getDefaults().getTopK(), "query-planner.defaults.top-k"),
                context.scoreThresholdEnabled(),
                context.scoreThreshold()
        );
        return new ExecutionPlan(
                PlanType.STANDARD_RETRIEVAL,
                context.query(),
                null,
                List.of(retrievalPlan),
                java.util.Map.of()
        );
    }

    private RetrievalPlan buildPlan(
            String query,
            List<KnowledgeBaseMeta> knowledgeBases,
            List<String> docIds,
            ModelMeta embeddingModel,
            Optional<ModelMeta> rerankModel,
            int topK,
            boolean requestedScoreThresholdEnabled,
            double requestedScoreThreshold
    ) {
        EmbeddingSpec embeddingSpec = new EmbeddingSpec(toModelEndpoint(embeddingModel));
        Map<String, List<String>> docIdsByKnowledgeBase = groupDocIdsByKnowledgeBase(docIds);

        List<KnowledgeBaseRecallSpec> recallSpecs = knowledgeBases.stream()
                .filter(kb -> docIds.isEmpty() || docIdsByKnowledgeBase.containsKey(kb.knowledgeBaseId()))
                .map(kb -> {
                    int recallTopK = resolveKnowledgeBaseRecallTopK(kb.topK());
                    List<String> knowledgeBaseDocIds = docIds.isEmpty()
                            ? List.of()
                            : docIdsByKnowledgeBase.getOrDefault(kb.knowledgeBaseId(), List.of());
                    return new KnowledgeBaseRecallSpec(
                            kb.knowledgeBaseId(),
                            kb.collectionName(),
                            kb.retrievalMode(),
                            resolveBindings(kb),
                            knowledgeBaseDocIds,
                            recallTopK,
                            recallTopK,
                            kb.rerankingEnabled(),
                            kb.scoreThresholdEnabled(),
                            kb.scoreThreshold(),
                            kb.vectorWeight(),
                            kb.keywordWeight(),
                            buildKnowledgeBaseRerankSpec(kb, rerankModel)
                    );
                })
                .toList();
        if (recallSpecs.isEmpty()) {
            throw new IllegalStateException("knowledge base scope is empty after document filtering");
        }

        log.info("召回规格构建完成，库数={}，请求docId数={}，topK={}，各库docId过滤数={}，详情={}",
                recallSpecs.size(),
                docIds.size(),
                topK,
                recallSpecs.stream()
                        .collect(Collectors.toMap(
                                KnowledgeBaseRecallSpec::knowledgeBaseId,
                                spec -> spec.docIds().size(),
                                (left, right) -> left,
                                LinkedHashMap::new
                        )),
                recallSpecs.stream()
                        .map(s -> s.knowledgeBaseId() + "[" + s.retrievalMode() + "]")
                        .toList());

        RankingSpec globalRankingSpec = buildGlobalRankingSpec(recallSpecs, rerankModel);

        ThresholdSetting globalScoreThreshold = resolveGlobalScoreThreshold(
                requestedScoreThresholdEnabled,
                requestedScoreThreshold
        );

        log.info("全局排序策略={}，排序模式={}，weighted回退原因={}，全局阈值开关={}，全局阈值={}，阈值聚合策略={}，rerank模型={}，可单库rerank知识库数={}，知识库声明启用rerank数={}",
                globalRankingSpec.getClass().getSimpleName(),
                globalRankingSpec instanceof RankingSpec.RerankRankingSpec ? "global_rerank" : "weighted_fallback",
                resolveWeightedFallbackReason(recallSpecs, rerankModel).orElse("none"),
                globalScoreThreshold.enabled(),
                globalScoreThreshold.threshold(),
                globalScoreThreshold.source(),
                rerankModel.map(ModelMeta::modelId).orElse("none"),
                recallSpecs.stream().filter(KnowledgeBaseRecallSpec::hasRerankRankingSpec).count(),
                recallSpecs.stream().filter(KnowledgeBaseRecallSpec::rerankingEnabled).count());

        return new RetrievalPlan(
                query,
                embeddingSpec,
                recallSpecs,
                globalRankingSpec,
                topK,
                globalScoreThreshold.enabled(),
                globalScoreThreshold.threshold()
        );
    }

    private Map<String, List<String>> groupDocIdsByKnowledgeBase(List<String> docIds) {
        if (docIds.isEmpty()) {
            return Map.of();
        }
        Map<String, DocumentMeta> documentMetas = metadataQueryService.getDocumentMetas(docIds);
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String docId : docIds) {
            DocumentMeta meta = documentMetas.get(docId);
            if (meta == null) {
                continue;
            }
            grouped.computeIfAbsent(meta.knowledgeBaseId(), ignored -> new java.util.ArrayList<>()).add(docId);
        }
        if (grouped.isEmpty()) {
            log.warn("请求docIds未能匹配到任何文档元数据，requestDocCount={}，sampleDocIds={}",
                    docIds.size(),
                    docIds.stream().limit(5).toList());
        }
        return grouped.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())
                ));
    }

    private RankingSpec buildGlobalRankingSpec(
            List<KnowledgeBaseRecallSpec> recallSpecs,
            Optional<ModelMeta> rerankModel
    ) {
        boolean globalRerankEnabled = recallSpecs.stream().anyMatch(KnowledgeBaseRecallSpec::rerankingEnabled);
        return rerankModel
                .filter(model -> globalRerankEnabled)
                .<RankingSpec>map(model -> new RankingSpec.RerankRankingSpec(toModelEndpoint(model)))
                .orElseGet(() -> new RankingSpec.WeightedRankingSpec(
                        QueryPlannerDefaults.VECTOR_WEIGHT, QueryPlannerDefaults.KEYWORD_WEIGHT));
    }

    private Optional<String> resolveWeightedFallbackReason(
            List<KnowledgeBaseRecallSpec> recallSpecs,
            Optional<ModelMeta> rerankModel
    ) {
        if (rerankModel.isEmpty()) {
            return Optional.of("missing_rerank_model");
        }
        boolean anyKnowledgeBaseEnabled = recallSpecs.stream().anyMatch(KnowledgeBaseRecallSpec::rerankingEnabled);
        if (!anyKnowledgeBaseEnabled) {
            return Optional.of("no_knowledge_base_rerank_enabled");
        }
        return Optional.empty();
    }

    private ThresholdSetting resolveGlobalScoreThreshold(
            boolean requestedScoreThresholdEnabled,
            double requestedScoreThreshold
    ) {
        if (requestedScoreThresholdEnabled) {
            return new ThresholdSetting(true, requestedScoreThreshold, "request_score_threshold");
        }
        return ThresholdSetting.disabled("no_request_score_threshold");
    }

    private int resolveKnowledgeBaseRecallTopK(Integer knowledgeBaseTopK) {
        if (knowledgeBaseTopK == null || knowledgeBaseTopK <= 0) {
            return DIFY_DEFAULT_DATASET_TOP_K;
        }
        return knowledgeBaseTopK;
    }

    private RankingSpec.RerankRankingSpec buildKnowledgeBaseRerankSpec(
            KnowledgeBaseMeta knowledgeBase,
            Optional<ModelMeta> rerankModel
    ) {
        if (!knowledgeBase.rerankingEnabled()) {
            return null;
        }
        return rerankModel
                .map(model -> new RankingSpec.RerankRankingSpec(toModelEndpoint(model)))
                .orElse(null);
    }

    private List<RetrievalBinding> resolveBindings(KnowledgeBaseMeta knowledgeBase) {
        List<RetrievalBinding> bindings = knowledgeBase.bindings().stream()
                .filter(com.cffex.rag.common.domain.metadata.RetrievalBindingMeta::enabled)
                .map(binding -> new RetrievalBinding(
                        binding.capability(),
                        binding.engineId(),
                        binding.targetName(),
                        binding.options()
                ))
                .toList();

        for (RetrievalCapability capability : requiredCapabilities(knowledgeBase.retrievalMode())) {
            boolean present = bindings.stream().anyMatch(binding -> binding.capability() == capability);
            if (!present) {
                throw new IllegalStateException(
                        "knowledge base missing required binding, knowledgeBaseId=%s, mode=%s, capability=%s"
                                .formatted(
                                        knowledgeBase.knowledgeBaseId(),
                                        knowledgeBase.retrievalMode(),
                                        capability
                                )
                );
            }
        }
        return bindings;
    }

    private List<RetrievalCapability> requiredCapabilities(RetrievalMode retrievalMode) {
        return switch (retrievalMode) {
            case SEMANTIC -> List.of(RetrievalCapability.VECTOR);
            case FULL_TEXT -> List.of(RetrievalCapability.FULL_TEXT);
            case HYBRID -> List.of(RetrievalCapability.VECTOR, RetrievalCapability.FULL_TEXT);
        };
    }

    private List<KnowledgeBaseMeta> resolveKnowledgeBases(QueryPlanRequest request) {
        List<KnowledgeBaseMeta> knowledgeBases = metadataQueryService.listKnowledgeBases(
                new KnowledgeBaseQueryCondition(request.docIds())
        );
        if (knowledgeBases.isEmpty()) {
            throw new IllegalStateException("knowledge base scope is empty after planning");
        }
        return knowledgeBases;
    }

    private List<KnowledgeBaseMeta> resolveKnowledgeBases(List<String> targetKnowledgeBaseIds) {
        List<KnowledgeBaseMeta> allKnowledgeBases = metadataQueryService.listKnowledgeBases(KnowledgeBaseQueryCondition.all());
        Set<String> targetIdSet = Set.copyOf(targetKnowledgeBaseIds);
        List<KnowledgeBaseMeta> selected = allKnowledgeBases.stream()
                .filter(kb -> targetIdSet.contains(kb.knowledgeBaseId()))
                .toList();
        if (selected.size() != targetKnowledgeBaseIds.size()) {
            Set<String> resolvedIdSet = selected.stream()
                    .map(KnowledgeBaseMeta::knowledgeBaseId)
                    .collect(Collectors.toSet());
            List<String> missing = targetKnowledgeBaseIds.stream()
                    .filter(id -> !resolvedIdSet.contains(id))
                    .toList();
            throw new IllegalStateException("knowledge base not found or disabled: " + missing);
        }
        return selected;
    }

    private ModelMeta selectRequiredModel(ModelType type, String configuredModelId) {
        String configuredValue = configuredModelId;
        if (configuredValue == null || configuredValue.isBlank()) {
            throw new IllegalStateException("missing configured default model id for type: " + type);
        }
        return findModel(type, configuredValue)
                .orElseThrow(() -> new IllegalStateException(
                        "model not found or disabled for type " + type + ": " + configuredValue));
    }

    private Optional<ModelMeta> selectOptionalModel(ModelType type, String configuredModelId) {
        if (configuredModelId == null || configuredModelId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(findModel(type, configuredModelId)
                .orElseThrow(() -> new IllegalStateException(
                        "model not found or disabled for type " + type + ": " + configuredModelId)));
    }

    private Optional<ModelMeta> findModel(ModelType type, String configuredValue) {
        String normalizedConfiguredValue = normalize(configuredValue);
        return metadataQueryService.listModels(new ModelQueryCondition(type, null)).stream()
                .filter(model -> matchesModel(model, configuredValue, normalizedConfiguredValue))
                .findFirst();
    }

    /**
     * 模型选择优先按 modelName 匹配，兼容历史配置继续按 modelId 命中。
     */
    private boolean matchesModel(ModelMeta model, String configuredValue, String normalizedConfiguredValue) {
        if (configuredValue.equals(model.modelName())) {
            return true;
        }
        if (configuredValue.equals(model.modelId())) {
            return true;
        }
        String normalizedModelName = normalize(model.modelName());
        if (normalizedConfiguredValue != null && normalizedConfiguredValue.equals(normalizedModelName)) {
            return true;
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonBlank(String primary, String fallback) {
        String normalizedPrimary = primary == null || primary.isBlank() ? null : primary;
        return normalizedPrimary != null ? normalizedPrimary : (fallback == null || fallback.isBlank() ? null : fallback);
    }

    private int requirePositive(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalStateException(propertyName + " must be positive");
        }
        return value;
    }

    private ModelEndpointSpec toModelEndpoint(ModelMeta modelMeta) {
        return new ModelEndpointSpec(
                modelMeta.baseUrl(),
                modelMeta.apiKey(),
                modelMeta.modelName()
        );
    }

    private record ThresholdSetting(boolean enabled, double threshold, String source) {
        private static ThresholdSetting disabled(String source) {
            return new ThresholdSetting(false, DISABLED_GLOBAL_SCORE_THRESHOLD, source);
        }
    }
}
