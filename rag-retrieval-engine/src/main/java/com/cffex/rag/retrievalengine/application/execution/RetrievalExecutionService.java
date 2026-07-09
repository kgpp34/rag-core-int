package com.cffex.rag.retrievalengine.application.execution;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cffex.rag.common.domain.metadata.DocumentMeta;
import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.service.MetadataQueryService;
import com.cffex.rag.common.service.RagProcessEventPublisher;
import com.cffex.rag.common.service.RagProcessEventPublisher.RagProcessStage;
import com.cffex.rag.common.service.RagProcessEventPublisher.StageHandle;
import com.cffex.rag.retrievalengine.application.RerankRankingService;
import com.cffex.rag.retrievalengine.application.WeightedRankingService;
import com.cffex.rag.retrievalengine.application.command.KnowledgeBaseRecallCommand;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.command.WeightedGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.application.query.ProcessedQuery;
import com.cffex.rag.retrievalengine.application.query.QueryPreprocessor;
import com.cffex.rag.retrievalengine.application.retrieval.MultiKnowledgeBaseRecallService;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.EmbeddingModelPolicy;
import com.cffex.rag.retrievalengine.domain.model.GlobalRerankPolicy;
import com.cffex.rag.retrievalengine.domain.model.GlobalRankingPolicy;
import com.cffex.rag.retrievalengine.domain.model.KnowledgeBaseRecallPolicy;
import com.cffex.rag.retrievalengine.domain.port.QueryEmbeddingPort;
import com.cffex.rag.retrievalengine.domain.service.GlobalRankingDomainService;

/**
 * 默认检索执行应用服务。
 *
 * <p>10.5 起不再保留单实现 registry 式过渡层，
 * 默认执行链路收拢到 `application.execution`。
 */
@Component
public class RetrievalExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalExecutionService.class);

    private final QueryPreprocessor queryPreprocessor;
    private final MultiKnowledgeBaseRecallService multiKnowledgeBaseRecallService;
    private final MetadataQueryService metadataQueryService;
    private final WeightedRankingService weightedRankingService;
    private final RerankRankingService rerankRankingService;
    private final GlobalRankingDomainService globalRankingDomainService;
    private final QueryEmbeddingPort queryEmbeddingPort;
    private final RetrievalDebugTraceWriter debugTraceWriter;

    public RetrievalExecutionService(
            QueryPreprocessor queryPreprocessor,
            MultiKnowledgeBaseRecallService multiKnowledgeBaseRecallService,
            MetadataQueryService metadataQueryService,
            WeightedRankingService weightedRankingService,
            RerankRankingService rerankRankingService,
            GlobalRankingDomainService globalRankingDomainService,
            QueryEmbeddingPort queryEmbeddingPort,
            RetrievalDebugTraceWriter debugTraceWriter
    ) {
        this.queryPreprocessor = Objects.requireNonNull(queryPreprocessor);
        this.multiKnowledgeBaseRecallService = Objects.requireNonNull(multiKnowledgeBaseRecallService);
        this.metadataQueryService = Objects.requireNonNull(metadataQueryService);
        this.weightedRankingService = Objects.requireNonNull(weightedRankingService);
        this.rerankRankingService = Objects.requireNonNull(rerankRankingService);
        this.globalRankingDomainService = Objects.requireNonNull(globalRankingDomainService);
        this.queryEmbeddingPort = Objects.requireNonNull(queryEmbeddingPort);
        this.debugTraceWriter = Objects.requireNonNull(debugTraceWriter);
    }

    public RetrievalExecutionResult execute(RetrievalExecutionContext context) {
        long totalStart = System.nanoTime();
        RetrievalTrace trace = context.trace();
        com.cffex.rag.retrievalengine.domain.model.RetrievalSession session = context.session();
        GlobalRankingPolicy rankingPolicy = session.globalRankingPolicy();
        long knowledgeBaseRerankCount = session.knowledgeBaseRerankCount();
        boolean globalRerankEnabled = session.globalRerankEnabled();

        // 第 1 步：query 预处理
        long preprocessStart = System.nanoTime();
        ProcessedQuery preprocessedQuery = queryPreprocessor.preprocess(context);
        trace.recordQueryPreprocessing(session.rawQueryText(), preprocessedQuery.text());
        if (debugTraceWriter.enabled()) {
            debugTraceWriter.record("query.preprocess", Map.of(
                    "rawQuery", session.rawQueryText(),
                    "effectiveQuery", preprocessedQuery.text(),
                    "queryRewritten", !session.rawQueryText().equals(preprocessedQuery.text())
            ));
        }
        long preprocessMs = durationMs(preprocessStart);
        session = session.withProcessedQueryText(preprocessedQuery.text());
        RetrievalExecutionContext updatedContext = context.withSession(session);
        log.debug("query预处理 | ms={}, queryRewritten={}", preprocessMs, trace.queryRewritten());

        // 第 2 步：query embedding
        if (requiresDenseEmbedding(updatedContext)) {
            long embedStart = System.nanoTime();
            EmbeddingModelPolicy embeddingModel = toEmbeddingModel(updatedContext);
            float[] queryVector = queryEmbeddingPort.embed(preprocessedQuery.text(), embeddingModel);
            long embedMs = durationMs(embedStart);
            trace.recordEmbedding(embedMs, queryVector != null ? queryVector.length : 0);
            updatedContext = updatedContext.withQueryVector(queryVector);
            log.debug("embedding | ms={}, dim={}", embedMs, queryVector != null ? queryVector.length : 0);
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("embedding.query", Map.of(
                        "model", embeddingModel.model(),
                        "endpoint", embeddingModel.endpoint(),
                        "input", preprocessedQuery.text(),
                        "elapsedMs", embedMs,
                        "vector", debugTraceWriter.embeddingSummary(queryVector)
                ));
                debugTraceWriter.record("embedding.completed", Map.of(
                        "model", embeddingModel.model(),
                        "endpoint", embeddingModel.endpoint(),
                        "inputLength", preprocessedQuery.text().length(),
                        "elapsedMs", embedMs,
                        "dimension", queryVector != null ? queryVector.length : 0
                ));
            }
        } else if (debugTraceWriter.enabled()) {
            debugTraceWriter.record("embedding.skipped", Map.of(
                    "reason", "no_dense_route",
                    "recallSpecCount", context.command().recallCommands().size()
            ));
        }

        // 第 3 步：多库并行召回
        long recallStart = System.nanoTime();
        List<RetrievalCandidate> candidates = multiKnowledgeBaseRecallService.recall(preprocessedQuery, updatedContext);
        long recallMs = durationMs(recallStart);
        int kbCount = session.recallPolicies().size();
        trace.recordRecall(recallMs, kbCount, candidates.size());
        if (debugTraceWriter.enabled()) {
            debugTraceWriter.record("recall.completed", Map.of(
                    "kbCount", kbCount,
                    "candidateCount", candidates.size(),
                    "elapsedMs", recallMs
            ));
        }
        session = session.withRecalledCandidates(candidates);
        if (candidates.isEmpty()) {
            log.warn("检索召回结果为空，问题长度={}，召回规格数={}",
                    session.rawQueryText().length(),
                    session.recallPolicies().size());
        }

        // 第 4 步：补全文档元数据
        long enrichStart = System.nanoTime();
        List<RetrievalCandidate> enrichedCandidates = orderGlobalCandidates(enrichCandidates(candidates));
        long enrichMs = durationMs(enrichStart);
        trace.recordMetadataEnrich(enrichMs);
        if (debugTraceWriter.enabled()) {
            debugTraceWriter.record("metadata.enrich.completed", Map.of(
                    "inputCount", candidates.size(),
                    "outputCount", enrichedCandidates.size(),
                    "elapsedMs", enrichMs
            ));
        }
        session = session.withEnrichedCandidates(enrichedCandidates);
        recordGlobalMergeBeforeRerank(session.enrichedCandidates(), rankingPolicy, globalRerankEnabled);

        // 第 5 步：请求级排序
        long rankingStart = System.nanoTime();
        boolean singleKbSkipGlobalRerank = singleKnowledgeBaseAlreadyReranked(session.recallPolicies(), globalRerankEnabled);
        RagProcessEventPublisher eventPublisher = updatedContext.eventPublisher();
        String rankingMode;
        List<RetrievedChunk> chunks;
        if (singleKbSkipGlobalRerank) {
            rankingMode = "single_kb_skip_global_rerank";
            recordGlobalRerankSkipped("single_kb_skip_global_rerank", session.enrichedCandidates());
            chunks = singleKnowledgeBaseRank(session.enrichedCandidates(), rankingPolicy);
        } else {
            rankingMode = globalRerankEnabled ? "global_rerank" : "weighted_fallback";
            if (!globalRerankEnabled) {
                recordGlobalRerankSkipped("global_rerank_disabled", session.enrichedCandidates());
            }
            chunks = globalRankingDomainService.rank(
                    session.effectiveQueryText(),
                    session.enrichedCandidates(),
                    rankingPolicy,
                    (queryText, candidatesToRank, weightedPolicy) -> weightedRankingService.rank(
                            queryText,
                            candidatesToRank,
                            new WeightedGlobalRankingCommand(weightedPolicy.vectorWeight(), weightedPolicy.keywordWeight()),
                            weightedPolicy.topK()
                    ),
                    (queryText, candidatesToRank, rerankPolicy, rerankTopN) -> rerank(
                            queryText,
                            candidatesToRank,
                            rerankPolicy,
                            rerankTopN,
                            eventPublisher
                    )
            );
        }
        long rankingMs = durationMs(rankingStart);
        trace.recordRanking(rankingMode, rankingMs);

        if (globalRerankEnabled && !singleKbSkipGlobalRerank) {
            long rerankMs = rankingMs;
            trace.recordGlobalRerank(rerankMs);
        }

        if (chunks.isEmpty()) {
            log.warn("检索排序后无结果，候选数={}，排序策略={}",
                    session.enrichedCandidates().size(),
                    rankingPolicy.getClass().getSimpleName());
        }
        recordGlobalFinal(chunks, rankingMode);

        long totalMs = durationMs(totalStart);
        trace.recordTotal(totalMs, chunks.size());
        if (debugTraceWriter.enabled()) {
            debugTraceWriter.record("retrieval.execution.completed", Map.of(
                    "totalMs", totalMs,
                    "resultCount", chunks.size(),
                    "candidateCount", session.enrichedCandidates().size(),
                    "rankingMode", rankingMode,
                    "kbTraceCount", trace.kbTraces().size()
            ));
        }

        log.info(trace.toInfoSummary());
        if (log.isDebugEnabled()) {
            for (String detail : trace.toDebugDetails()) {
                log.debug(detail);
            }
        }

        boolean effectiveGlobalRerankEnabled = globalRerankEnabled && !singleKbSkipGlobalRerank;
        boolean weightedFallback = "weighted_fallback".equals(rankingMode);
        return new RetrievalExecutionResult(
                chunks,
                session.enrichedCandidates().size(),
                rankingPolicy.getClass().getSimpleName(),
                rankingMode,
                effectiveGlobalRerankEnabled,
                rankingPolicy.scoreThresholdEnabled(),
                rankingPolicy.scoreThreshold(),
                weightedFallback,
                knowledgeBaseRerankCount,
                chunks.size()
        );
    }

    private List<RetrievalCandidate> enrichCandidates(List<RetrievalCandidate> candidates) {
        List<String> documentIds = candidates.stream()
                .map(RetrievalCandidate::documentId)
                .filter(documentId -> !documentId.isBlank())
                .distinct()
                .toList();
        if (documentIds.isEmpty()) {
            return candidates;
        }
        Map<String, DocumentMeta> documentMetas = metadataQueryService.getDocumentMetas(documentIds);
        return candidates.stream()
                .map(candidate -> enrichCandidate(candidate, documentMetas.get(candidate.documentId())))
                .toList();
    }

    private List<RetrievalCandidate> orderGlobalCandidates(List<RetrievalCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::finalScore).reversed())
                .toList();
    }

    private RetrievalCandidate enrichCandidate(RetrievalCandidate candidate, DocumentMeta documentMeta) {
        if (documentMeta == null) {
            return candidate;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(candidate.metadata());
        if (documentMeta.name() != null) {
            metadata.put("document_name", documentMeta.name());
        }
        if (documentMeta.uploadFileId() != null) {
            metadata.put("upload_file_id", documentMeta.uploadFileId());
        }
        return new RetrievalCandidate(
                candidate.chunkId(),
                candidate.documentId(),
                candidate.knowledgeBaseId(),
                candidate.content(),
                candidate.finalScore(),
                candidate.sparseScore(),
                metadata
        );
    }

    private List<RetrievedChunk> singleKnowledgeBaseRank(
            List<RetrievalCandidate> candidates,
            GlobalRankingPolicy policy) {
        return candidates.stream()
                .sorted((a, b) -> Double.compare(b.finalScore(), a.finalScore()))
                .map(c -> new RetrievedChunk(
                        c.chunkId(),
                        c.documentId(),
                        c.knowledgeBaseId(),
                        c.finalScore(),
                        c.sparseScore(),
                        c.finalScore(),
                        c.content(),
                        c.metadata()))
                .limit(policy.topK())
                .toList();
    }

    private boolean singleKnowledgeBaseAlreadyReranked(
            List<KnowledgeBaseRecallPolicy> recallPolicies,
            boolean globalRerankEnabled
    ) {
        return globalRerankEnabled
                && recallPolicies.size() == 1
                && recallPolicies.get(0).hasRerankPolicy();
    }

    private List<RetrievedChunk> rerank(
            String queryText,
            List<RetrievalCandidate> candidates,
            GlobalRerankPolicy rerankPolicy,
            int rerankTopN,
            RagProcessEventPublisher eventPublisher
    ) {
        recordGlobalRerankRequest(candidates, rerankPolicy, rerankTopN);
        long rerankStart = System.nanoTime();
        StageHandle handle = eventPublisher.start(RagProcessStage.RERANK, Map.of(
                "scope", "global",
                "inputCount", candidates.size()
        ));
        try {
            List<RetrievedChunk> chunks = rerankRankingService.rank(
                    queryText,
                    candidates,
                    new RerankGlobalRankingCommand(new ModelEndpointCommand(
                            rerankPolicy.rerankPolicy().endpoint(),
                            rerankPolicy.rerankPolicy().authToken(),
                            rerankPolicy.rerankPolicy().model()
                    )),
                    rerankTopN,
                    rerankPolicy.scoreThresholdEnabled() ? rerankPolicy.scoreThreshold() : 0.0d
            );
            long rerankMs = durationMs(rerankStart);
            eventPublisher.complete(handle, Map.of(
                    "scope", "global",
                    "inputCount", candidates.size(),
                    "outputCount", chunks.size()
            ));
            if (debugTraceWriter.enabled()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("scope", "global");
                payload.put("model", rerankPolicy.rerankPolicy().model());
                payload.put("endpoint", rerankPolicy.rerankPolicy().endpoint());
                payload.put("inputCount", candidates.size());
                payload.put("outputCount", chunks.size());
                payload.put("topK", rerankTopN);
                payload.put("scoreThresholdEnabled", rerankPolicy.scoreThresholdEnabled());
                payload.put("scoreThreshold", rerankPolicy.scoreThreshold());
                payload.put("elapsedMs", rerankMs);
                debugTraceWriter.record("global.rerank.completed", payload);
            }
            recordGlobalRerankResponse(chunks);
            return chunks;
        } catch (RuntimeException ex) {
            eventPublisher.fail(handle, RagErrorCode.RETRIEVAL_FAILED.name());
            throw ex;
        }
    }

    private boolean requiresDenseEmbedding(RetrievalExecutionContext context) {
        return context.command().recallCommands().stream()
                .map(KnowledgeBaseRecallCommand::recallPolicy)
                .anyMatch(KnowledgeBaseRecallPolicy::usesDenseRoute);
    }

    private EmbeddingModelPolicy toEmbeddingModel(RetrievalExecutionContext context) {
        return new EmbeddingModelPolicy(
                context.command().embeddingModel().modelEndpoint().endpoint(),
                context.command().embeddingModel().modelEndpoint().authToken(),
                context.command().embeddingModel().modelEndpoint().model()
        );
    }

    private static long durationMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private void recordGlobalMergeBeforeRerank(
            List<RetrievalCandidate> candidates,
            GlobalRankingPolicy policy,
            boolean globalRerankEnabled
    ) {
        if (!debugTraceWriter.enabled()) {
            return;
        }
        debugTraceWriter.record("global.merge.before_rerank", Map.of(
                "rankingPolicy", policy.getClass().getSimpleName(),
                "globalRerankEnabled", globalRerankEnabled,
                "topK", policy.topK(),
                "inputCount", candidates.size(),
                "candidates", debugTraceWriter.candidates(candidates)
        ));
    }

    private void recordGlobalRerankSkipped(String reason, List<RetrievalCandidate> candidates) {
        if (!debugTraceWriter.enabled()) {
            return;
        }
        debugTraceWriter.record("global.rerank.skipped", Map.of(
                "scope", "global",
                "reason", reason,
                "inputCount", candidates.size()
        ));
    }

    private void recordGlobalRerankRequest(
            List<RetrievalCandidate> candidates,
            GlobalRerankPolicy policy,
            int topN
    ) {
        if (!debugTraceWriter.enabled()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "global");
        payload.put("model", policy.rerankPolicy().model());
        payload.put("topK", topN);
        payload.put("scoreThresholdEnabled", policy.scoreThresholdEnabled());
        if (policy.scoreThresholdEnabled()) {
            payload.put("scoreThreshold", policy.scoreThreshold());
        }
        payload.put("inputCount", candidates.size());
        payload.put("candidates", debugTraceWriter.candidates(candidates));
        debugTraceWriter.record("global.rerank.request", payload);
    }

    private void recordGlobalRerankResponse(List<RetrievedChunk> chunks) {
        if (!debugTraceWriter.enabled()) {
            return;
        }
        debugTraceWriter.record("global.rerank.response", Map.of(
                "scope", "global",
                "outputCount", chunks.size(),
                "chunks", debugTraceWriter.chunks(chunks)
        ));
    }

    private void recordGlobalFinal(List<RetrievedChunk> chunks, String rankingMode) {
        if (!debugTraceWriter.enabled()) {
            return;
        }
        debugTraceWriter.record("global.final", Map.of(
                "rankingMode", rankingMode,
                "count", chunks.size(),
                "chunks", debugTraceWriter.chunks(chunks)
        ));
    }
}
