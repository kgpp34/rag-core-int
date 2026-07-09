package com.cffex.rag.retrievalengine.application.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.service.RagProcessEventPublisher;
import com.cffex.rag.common.service.RagProcessEventPublisher.RagProcessStage;
import com.cffex.rag.common.service.RagProcessEventPublisher.StageHandle;
import com.cffex.rag.retrievalengine.application.RerankRankingService;
import com.cffex.rag.retrievalengine.application.command.KnowledgeBaseRecallCommand;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.application.execution.RetrievalTrace;
import com.cffex.rag.retrievalengine.application.port.ParallelExecutionStrategy;
import com.cffex.rag.retrievalengine.domain.DenseVectorSearchRequest;
import com.cffex.rag.retrievalengine.domain.FullTextSearchRequest;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.domain.model.KnowledgeBaseRecallPolicy;
import com.cffex.rag.retrievalengine.domain.service.KnowledgeBaseRecallDomainService;

/**
 * 单知识库闭环检索任务。
 *
 * <p>在知识库粒度内完成 dense/sparse 检索、融合与单库 rerank，
 * 供多知识库组合检索器并行调度。
 */
@Component
public class KnowledgeBaseRetrievalTask {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseRetrievalTask.class);

    private final RetrieverRegistry retrieverRegistry;
    private final ParallelExecutionStrategy parallelExecutionStrategy;
    private final RerankRankingService rerankRankingService;
    private final KnowledgeBaseRecallDomainService knowledgeBaseRecallDomainService;
    private final RetrievalDebugTraceWriter debugTraceWriter;

    private enum RecallRoute {
        DENSE,
        SPARSE
    }

    private record RouteResult(RecallRoute route, List<RetrievalCandidate> candidates) {
    }

    public KnowledgeBaseRetrievalTask(
            RetrieverRegistry retrieverRegistry,
            ParallelExecutionStrategy parallelExecutionStrategy,
            RerankRankingService rerankRankingService,
            KnowledgeBaseRecallDomainService knowledgeBaseRecallDomainService,
            RetrievalDebugTraceWriter debugTraceWriter
    ) {
        this.retrieverRegistry = Objects.requireNonNull(retrieverRegistry);
        this.parallelExecutionStrategy = Objects.requireNonNull(parallelExecutionStrategy);
        this.rerankRankingService = Objects.requireNonNull(rerankRankingService);
        this.knowledgeBaseRecallDomainService = Objects.requireNonNull(knowledgeBaseRecallDomainService);
        this.debugTraceWriter = Objects.requireNonNull(debugTraceWriter);
    }

    public List<RetrievalCandidate> execute(
            String queryText,
            float[] queryVector,
            KnowledgeBaseRecallCommand spec
    ) {
        return execute(queryText, queryVector, spec, null);
    }

    public List<RetrievalCandidate> execute(
            String queryText,
            float[] queryVector,
            KnowledgeBaseRecallCommand spec,
            RetrievalTrace trace
    ) {
        return execute(queryText, queryVector, spec, trace, RagProcessEventPublisher.NO_OP);
    }

    public List<RetrievalCandidate> execute(
            String queryText,
            float[] queryVector,
            KnowledgeBaseRecallCommand spec,
            RetrievalTrace trace,
            RagProcessEventPublisher eventPublisher
    ) {
        Objects.requireNonNull(queryText, "queryText must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        KnowledgeBaseRecallPolicy policy = spec.recallPolicy();
        long taskStart = System.nanoTime();
        if (debugTraceWriter.enabled()) {
            debugTraceWriter.record("kb.recall.started", Map.of(
                    "knowledgeBaseId", spec.knowledgeBaseId(),
                    "retrievalMode", spec.retrievalMode().name(),
                    "routeCandidateK", policy.routeCandidateK(),
                    "docFilterCount", spec.docIds().size()
            ));
        }

        List<Callable<RouteResult>> routeTasks = new ArrayList<>();
        int routeCandidateK = policy.routeCandidateK();

        if (policy.usesDenseRoute()) {
            float[] denseQueryVector = Objects.requireNonNull(queryVector, "queryVector must not be null for dense recall");
            routeTasks.add(() -> new RouteResult(
                    RecallRoute.DENSE,
                    searchDense(spec, denseQueryVector, routeCandidateK)
            ));
        }
        if (policy.usesSparseRoute()) {
            routeTasks.add(() -> new RouteResult(
                    RecallRoute.SPARSE,
                    searchSparse(spec, queryText, routeCandidateK)
            ));
        }

        List<RouteResult> routeResults = parallelExecutionStrategy.invokeAll(routeTasks);
        List<RetrievalCandidate> dense = routeResults.stream()
                .filter(result -> result.route() == RecallRoute.DENSE)
                .flatMap(result -> result.candidates().stream())
                .toList();
        List<RetrievalCandidate> sparse = routeResults.stream()
                .filter(result -> result.route() == RecallRoute.SPARSE)
                .flatMap(result -> result.candidates().stream())
                .toList();
        logRouteCandidates(spec.knowledgeBaseId(), RecallRoute.DENSE, dense);
        logRouteCandidates(spec.knowledgeBaseId(), RecallRoute.SPARSE, sparse);
        recordKbCandidates("kb.dense.raw", spec, dense);
        recordKbCandidates("kb.sparse.raw", spec, sparse);

        boolean localPreRerankFiltering = !policy.hasRerankPolicy();
        List<RetrievalCandidate> denseFiltered = localPreRerankFiltering
                ? knowledgeBaseRecallDomainService.filterDenseCandidates(policy, dense)
                : dense;
        List<RetrievalCandidate> sparseTrimmed = localPreRerankFiltering
                ? knowledgeBaseRecallDomainService.trimSparseCandidates(policy, sparse)
                : sparse;
        List<RetrievalCandidate> mergedBeforeRerank = localPreRerankFiltering
                ? knowledgeBaseRecallDomainService.mergeCandidatesBeforeRerank(policy, denseFiltered, sparseTrimmed)
                : knowledgeBaseRecallDomainService.mergeCandidatesForRerank(dense, sparse);
        if (localPreRerankFiltering) {
            recordKbCandidates("kb.dense.filtered", spec, denseFiltered);
            recordKbCandidates("kb.sparse.trimmed", spec, sparseTrimmed);
        }
        recordKbCandidates("kb.merged.before_rerank", spec, mergedBeforeRerank);

        long rerankStart = System.nanoTime();
        AtomicBoolean didRerank = new AtomicBoolean(false);
        List<RetrievalCandidate> finalCandidates = knowledgeBaseRecallDomainService.recall(
                policy,
                new KnowledgeBaseRecallDomainService.RouteCandidates(dense, sparse),
                candidates -> {
                    didRerank.set(true);
                    return rerankIfNecessary(queryText, spec, policy, candidates, eventPublisher);
                }
        );
        long rerankMs = didRerank.get() ? (System.nanoTime() - rerankStart) / 1_000_000 : 0;
        long taskMs = (System.nanoTime() - taskStart) / 1_000_000;

        log.debug("库[{}] 闭环检索完成 | mode={}, ms={}, denseRaw={}, denseFiltered={}, sparseRaw={}, sparseTrimmed={}, final={}, rerank={}, rerankMs={}",
                spec.knowledgeBaseId(),
                spec.retrievalMode(),
                taskMs,
                dense.size(),
                denseFiltered.size(),
                sparse.size(),
                sparseTrimmed.size(),
                finalCandidates.size(),
                didRerank.get(),
                rerankMs);
        logFinalCandidates(spec.knowledgeBaseId(), finalCandidates);
        recordKbCandidates("kb.final", spec, finalCandidates);

        if (trace != null) {
            trace.addKbTrace(new RetrievalTrace.KbTrace(
                    spec.knowledgeBaseId(),
                    spec.retrievalMode().name(),
                    taskMs,
                    dense.size(),
                    denseFiltered.size(),
                    sparse.size(),
                    sparseTrimmed.size(),
                    finalCandidates.size(),
                    didRerank.get(),
                    rerankMs,
                    policy.scoreThresholdEnabled(),
                    policy.scoreThreshold()
            ));
        }
        return finalCandidates;
    }

    private void recordKbCandidates(
            String event,
            KnowledgeBaseRecallCommand spec,
            List<RetrievalCandidate> candidates
    ) {
        if (!debugTraceWriter.enabled()) {
            return;
        }
        debugTraceWriter.record(event, Map.of(
                "knowledgeBaseId", spec.knowledgeBaseId(),
                "retrievalMode", spec.retrievalMode().name(),
                "collectionName", spec.vectorBinding().targetName(),
                "count", candidates.size(),
                "candidates", debugTraceWriter.candidates(candidates)
        ));
    }

    private void logRouteCandidates(
            String knowledgeBaseId,
            RecallRoute route,
            List<RetrievalCandidate> candidates
    ) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("库[{}] {}原始候选 | count={}, top={}",
                knowledgeBaseId,
                route.name().toLowerCase(java.util.Locale.ROOT),
                candidates.size(),
                candidates.stream()
                        .limit(5)
                        .map(KnowledgeBaseRetrievalTask::candidateSummary)
                        .toList());
    }

    private void logFinalCandidates(String knowledgeBaseId, List<RetrievalCandidate> candidates) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("库[{}] 单库最终候选 | count={}, top={}",
                knowledgeBaseId,
                candidates.size(),
                candidates.stream()
                        .limit(20)
                        .map(KnowledgeBaseRetrievalTask::candidateSummary)
                        .toList());
    }

    private static String candidateSummary(RetrievalCandidate candidate) {
        return "chunkId=" + candidate.chunkId()
                + ", documentId=" + candidate.documentId()
                + ", docId=" + metadataValue(candidate, "doc_id")
                + ", score=" + candidate.finalScore()
                + ", sparseScore=" + candidate.sparseScore()
                + ", text=" + abbreviate(candidate.content(), 80);
    }

    private static Object metadataValue(RetrievalCandidate candidate, String key) {
        Object value = candidate.metadata().get(key);
        if (value != null) {
            return value;
        }
        Object metadata = candidate.metadata().get("metadata");
        if (metadata instanceof Map<?, ?> nested) {
            return nested.get(key);
        }
        return null;
    }

    private static String abbreviate(String text, int maxLength) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private List<RetrievalCandidate> searchDense(
            KnowledgeBaseRecallCommand spec,
            float[] denseQueryVector,
            int routeCandidateK
    ) {
        try {
            return retrieverRegistry.vectorSearchPort(spec.vectorBinding().engineId()).search(
                    new DenseVectorSearchRequest(
                            spec.knowledgeBaseId(),
                            spec.vectorBinding().engineId(),
                            spec.vectorBinding().targetName(),
                            spec.docIds(),
                            denseQueryVector,
                            routeCandidateK,
                            spec.scoreThresholdEnabled(),
                            spec.scoreThreshold()
                    )
            );
        } catch (RuntimeException ex) {
            log.warn("库[{}] dense召回失败 | collection={}, topK={}, docFilterCount={}, error={}",
                    spec.knowledgeBaseId(),
                    spec.vectorBinding().targetName(),
                    routeCandidateK,
                    spec.docIds().size(),
                    ex.getMessage());
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("kb.recall.failed", Map.of(
                        "knowledgeBaseId", spec.knowledgeBaseId(),
                        "route", "dense",
                        "collectionName", spec.vectorBinding().targetName(),
                        "error", Objects.toString(ex.getMessage(), ex.getClass().getSimpleName())
                ));
            }
            throw ex;
        }
    }

    private List<RetrievalCandidate> searchSparse(
            KnowledgeBaseRecallCommand spec,
            String queryText,
            int routeCandidateK
    ) {
        try {
            return retrieverRegistry.fullTextSearchPort(spec.fullTextBinding().engineId()).search(
                    new FullTextSearchRequest(
                            spec.knowledgeBaseId(),
                            spec.fullTextBinding().engineId(),
                            spec.fullTextBinding().targetName(),
                            spec.docIds(),
                            queryText,
                            routeCandidateK,
                            false,
                            0.0d
                    )
            );
        } catch (RuntimeException ex) {
            log.warn("库[{}] sparse召回失败 | collection={}, topK={}, docFilterCount={}, queryLength={}, error={}",
                    spec.knowledgeBaseId(),
                    spec.fullTextBinding().targetName(),
                    routeCandidateK,
                    spec.docIds().size(),
                    queryText.length(),
                    ex.getMessage());
            if (debugTraceWriter.enabled()) {
                debugTraceWriter.record("kb.recall.failed", Map.of(
                        "knowledgeBaseId", spec.knowledgeBaseId(),
                        "route", "sparse",
                        "collectionName", spec.fullTextBinding().targetName(),
                        "error", Objects.toString(ex.getMessage(), ex.getClass().getSimpleName())
                ));
            }
            throw ex;
        }
    }

    private List<RetrievalCandidate> rerankIfNecessary(
            String queryText,
            KnowledgeBaseRecallCommand spec,
            KnowledgeBaseRecallPolicy policy,
            List<RetrievalCandidate> candidates,
            RagProcessEventPublisher eventPublisher
    ) {
        if (!policy.hasRerankPolicy()) {
            recordRerankSkipped(spec, candidates);
            return candidates;
        }
        recordRerankRequest(spec, policy, candidates);
        StageHandle handle = eventPublisher.start(RagProcessStage.RERANK, Map.of(
                "scope", "knowledge_base",
                "inputCount", candidates.size()
        ));
        try {
            List<RetrievalCandidate> result = rerankRankingService.rerankCandidates(
                    queryText,
                    candidates,
                    toRerankCommand(policy),
                    policy.resolvedTopK(),
                    policy.scoreThresholdEnabled() ? policy.scoreThreshold() : 0.0d
            );
            eventPublisher.complete(handle, Map.of(
                    "scope", "knowledge_base",
                    "inputCount", candidates.size(),
                    "outputCount", result.size()
            ));
            recordRerankResponse(spec, result);
            return result;
        } catch (RuntimeException ex) {
            eventPublisher.fail(handle, RagErrorCode.RETRIEVAL_FAILED.name());
            throw ex;
        }
    }

    private static RerankGlobalRankingCommand toRerankCommand(KnowledgeBaseRecallPolicy policy) {
        return new RerankGlobalRankingCommand(new ModelEndpointCommand(
                policy.rerankPolicy().endpoint(),
                policy.rerankPolicy().authToken(),
                policy.rerankPolicy().model()
        ));
    }

    private void recordRerankSkipped(KnowledgeBaseRecallCommand spec, List<RetrievalCandidate> candidates) {
        if (!debugTraceWriter.enabled()) {
            return;
        }
        debugTraceWriter.record("kb.rerank.skipped", Map.of(
                "knowledgeBaseId", spec.knowledgeBaseId(),
                "scope", "knowledge_base",
                "reason", "rerank_disabled",
                "inputCount", candidates.size()
        ));
    }

    private void recordRerankRequest(
            KnowledgeBaseRecallCommand spec,
            KnowledgeBaseRecallPolicy policy,
            List<RetrievalCandidate> candidates
    ) {
        if (!debugTraceWriter.enabled()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knowledgeBaseId", spec.knowledgeBaseId());
        payload.put("scope", "knowledge_base");
        payload.put("model", policy.rerankPolicy().model());
        payload.put("topK", policy.resolvedTopK());
        payload.put("scoreThresholdEnabled", policy.scoreThresholdEnabled());
        if (policy.scoreThresholdEnabled()) {
            payload.put("scoreThreshold", policy.scoreThreshold());
        }
        payload.put("inputCount", candidates.size());
        payload.put("candidates", debugTraceWriter.candidates(candidates));
        debugTraceWriter.record("kb.rerank.request", payload);
    }

    private void recordRerankResponse(KnowledgeBaseRecallCommand spec, List<RetrievalCandidate> candidates) {
        if (!debugTraceWriter.enabled()) {
            return;
        }
        debugTraceWriter.record("kb.rerank.response", Map.of(
                "knowledgeBaseId", spec.knowledgeBaseId(),
                "scope", "knowledge_base",
                "outputCount", candidates.size(),
                "candidates", debugTraceWriter.candidates(candidates)
        ));
    }
}
