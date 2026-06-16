package com.cffex.rag.retrievalengine.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.cffex.rag.common.domain.query.RetrievalPlan;
import com.cffex.rag.common.domain.retrieval.KnowledgeBaseRecallSpec;
import com.cffex.rag.common.domain.retrieval.ModelEndpointSpec;
import com.cffex.rag.common.domain.retrieval.RankingSpec;
import com.cffex.rag.common.domain.retrieval.RetrievalResult;
import com.cffex.rag.common.domain.retrieval.RetrievedChunk;
import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.service.RagProcessEventPublisher;
import com.cffex.rag.common.service.RagProcessEventPublisher.RagProcessStage;
import com.cffex.rag.common.service.RagProcessEventPublisher.StageHandle;
import com.cffex.rag.common.service.RetrievalEngine;
import com.cffex.rag.retrievalengine.application.acl.RetrievalPlanMapper;
import com.cffex.rag.retrievalengine.application.command.ExecuteRetrievalCommand;
import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.application.debug.DebugTraceContext;
import com.cffex.rag.retrievalengine.application.debug.RetrievalDebugTraceWriter;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionContext;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionResult;
import com.cffex.rag.retrievalengine.application.execution.RetrievalExecutionService;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;

@Service
public class DefaultRetrievalEngine implements RetrievalEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultRetrievalEngine.class);
    private static final String MDC_REQUEST_ID = "retrievalRequestId";

    private final RetrievalPlanMapper retrievalPlanMapper;
    private final RetrievalExecutionService retrievalExecutionService;
    private final RerankRankingService rerankRankingService;
    private final DebugTraceContext debugTraceContext;
    private final RetrievalDebugTraceWriter debugTraceWriter;

    public DefaultRetrievalEngine(
            RetrievalPlanMapper retrievalPlanMapper,
            RetrievalExecutionService retrievalExecutionService,
            RerankRankingService rerankRankingService,
            DebugTraceContext debugTraceContext,
            RetrievalDebugTraceWriter debugTraceWriter
    ) {
        this.retrievalPlanMapper = Objects.requireNonNull(retrievalPlanMapper);
        this.retrievalExecutionService = Objects.requireNonNull(retrievalExecutionService);
        this.rerankRankingService = Objects.requireNonNull(rerankRankingService);
        this.debugTraceContext = Objects.requireNonNull(debugTraceContext);
        this.debugTraceWriter = Objects.requireNonNull(debugTraceWriter);
    }

    @Override
    public RetrievalResult execute(RetrievalPlan plan) {
        return execute(plan, RagProcessEventPublisher.NO_OP);
    }

    @Override
    public RetrievalResult execute(RetrievalPlan plan, RagProcessEventPublisher eventPublisher) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(MDC_REQUEST_ID, requestId);
        String debugTraceId = Objects.toString(MDC.get("traceId"), "");
        if (debugTraceId.isBlank()) {
            debugTraceId = requestId;
        }
        debugTraceContext.setTraceId(debugTraceId);
        try {
            long start = System.currentTimeMillis();
            long knowledgeBaseRerankCount = plan.recallSpecs().stream()
                    .filter(KnowledgeBaseRecallSpec::hasRerankRankingSpec)
                    .count();
            boolean globalRerankEnabled = plan.rankingSpec() instanceof RankingSpec.RerankRankingSpec;
            log.info("检索开始 | requestId={}, queryLength={}, kbCount={}, topK={}, rankingMode={}, globalRerank={}, kbRerankCount={}",
                    requestId,
                    plan.query().length(),
                    plan.recallSpecs().size(),
                    plan.topK(),
                    plan.rankingSpec().getClass().getSimpleName(),
                    globalRerankEnabled,
                    knowledgeBaseRerankCount);
            debugTraceWriter.record("retrieval.request", Map.of(
                    "requestId", requestId,
                    "query", plan.query(),
                    "topK", plan.topK(),
                    "recallSpecCount", plan.recallSpecs().size(),
                    "rankingMode", plan.rankingSpec().getClass().getSimpleName(),
                    "globalRerankEnabled", globalRerankEnabled,
                    "knowledgeBaseRerankCount", knowledgeBaseRerankCount
            ));

            ExecuteRetrievalCommand command = retrievalPlanMapper.toCommand(plan);
            RetrievalExecutionResult executionResult = retrievalExecutionService.execute(
                    new RetrievalExecutionContext(command, eventPublisher));

            log.info("检索结束 | requestId={}, candidateCount={}, resultCount={}, totalMs={}",
                    requestId,
                    executionResult.candidateCount(),
                    executionResult.chunks().size(),
                    System.currentTimeMillis() - start);

            return new RetrievalResult(
                    requestId,
                    executionResult.chunks(),
                    executionResult.toDebugTrace()
            );
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            debugTraceContext.clear();
        }
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, ModelEndpointSpec rerankModel, int topK) {
        return rerank(query, chunks, rerankModel, topK, RagProcessEventPublisher.NO_OP, "external");
    }

    @Override
    public List<RetrievedChunk> rerank(
            String query,
            List<RetrievedChunk> chunks,
            ModelEndpointSpec rerankModel,
            int topK,
            RagProcessEventPublisher eventPublisher,
            String scope
    ) {
        log.debug("执行 rerank | queryLength={}, inputCount={}, topK={}", query.length(), chunks.size(), topK);
        StageHandle handle = eventPublisher.start(RagProcessStage.RERANK, Map.of(
                "scope", scope,
                "inputCount", chunks.size()
        ));
        List<RetrievalCandidate> candidates = chunks.stream()
                .map(chunk -> new RetrievalCandidate(
                        chunk.chunkId(),
                        chunk.documentId(),
                        chunk.knowledgeBaseId(),
                        chunk.content(),
                        chunk.vectorScore(),
                        chunk.sparseScore(),
                        chunk.metadata()
                ))
                .toList();
        RerankGlobalRankingCommand command = new RerankGlobalRankingCommand(
                new ModelEndpointCommand(rerankModel.endpoint(), rerankModel.authToken(), rerankModel.model())
        );
        try {
            List<RetrievedChunk> result = rerankRankingService.rank(query, candidates, command, topK);
            List<Map<String, Object>> knowledgeBases = finalKnowledgeBases(result);
            eventPublisher.complete(handle, Map.of(
                    "scope", scope,
                    "inputCount", chunks.size(),
                    "outputCount", result.size(),
                    "knowledgeBaseCount", knowledgeBases.size(),
                    "knowledgeBases", knowledgeBases
            ));
            log.debug("rerank 完成 | inputCount={}, outputCount={}", chunks.size(), result.size());
            return result;
        } catch (RuntimeException ex) {
            eventPublisher.fail(handle, RagErrorCode.RETRIEVAL_FAILED.name());
            throw ex;
        }
    }

    private static List<Map<String, Object>> finalKnowledgeBases(List<RetrievedChunk> chunks) {
        Map<String, Map<String, Object>> knowledgeBases = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            knowledgeBases.computeIfAbsent(
                    chunk.knowledgeBaseId(),
                    knowledgeBaseId -> Map.of(
                            "knowledgeBaseId", knowledgeBaseId,
                            "name", knowledgeBaseName(chunk)
                    )
            );
        }
        return List.copyOf(knowledgeBases.values());
    }

    private static String knowledgeBaseName(RetrievedChunk chunk) {
        Object name = chunk.metadata().get("knowledgeBaseName");
        if (name == null || name.toString().isBlank()) {
            return chunk.knowledgeBaseId();
        }
        return name.toString();
    }
}
