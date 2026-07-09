package com.cffex.rag.retrievalengine.application.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class RetrievalTrace {

    private boolean queryRewritten;
    private String originalQuery;
    private String processedQuery;

    private long embeddingMs;
    private int embeddingDim;

    private long recallMs;
    private int recallKbCount;
    private int candidateCount;

    private String rankingMode;
    private long rankingMs;

    private long globalRerankMs;
    private int globalRerankPromptTokens;
    private int globalRerankTotalTokens;

    private long metadataEnrichMs;

    private long totalMs;
    private int resultCount;

    private final List<KbTrace> kbTraces = new CopyOnWriteArrayList<>();

    public static record KbTrace(
            String knowledgeBaseId,
            String retrievalMode,
            long recallMs,
            int denseRaw,
            int denseFiltered,
            int sparseRaw,
            int sparseTrimmed,
            int finalCount,
            boolean reranked,
            long rerankMs,
            boolean scoreThresholdEnabled,
            double scoreThreshold
    ) {}

    public void recordQueryPreprocessing(String originalQuery, String processedQuery) {
        this.originalQuery = originalQuery;
        this.processedQuery = processedQuery;
        this.queryRewritten = !originalQuery.equals(processedQuery);
    }

    public void recordEmbedding(long elapsedMs, int dim) {
        this.embeddingMs = elapsedMs;
        this.embeddingDim = dim;
    }

    public void recordEmbedding(long elapsedMs) {
        this.embeddingMs = elapsedMs;
    }

    public void recordRecall(long elapsedMs, int kbCount, int candidateCount) {
        this.recallMs = elapsedMs;
        this.recallKbCount = kbCount;
        this.candidateCount = candidateCount;
    }

    public void addKbTrace(KbTrace kbTrace) {
        this.kbTraces.add(kbTrace);
    }

    public void recordRanking(String mode, long elapsedMs) {
        this.rankingMode = mode;
        this.rankingMs = elapsedMs;
    }

    public void recordGlobalRerank(long elapsedMs, int promptTokens, int totalTokens) {
        this.globalRerankMs = elapsedMs;
        this.globalRerankPromptTokens = promptTokens;
        this.globalRerankTotalTokens = totalTokens;
    }

    public void recordGlobalRerank(long elapsedMs) {
        this.globalRerankMs = elapsedMs;
    }

    public void recordMetadataEnrich(long elapsedMs) {
        this.metadataEnrichMs = elapsedMs;
    }

    public void recordTotal(long elapsedMs, int resultCount) {
        this.totalMs = elapsedMs;
        this.resultCount = resultCount;
    }

    public String toInfoSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("检索完成 | ");
        sb.append("queryRewritten=").append(queryRewritten).append(" | ");
        if (embeddingMs > 0) {
            sb.append("embeddingMs=").append(embeddingMs).append(" | ");
        }
        sb.append("recallMs=").append(recallMs);
        sb.append(", recallKbCount=").append(recallKbCount);
        sb.append(", candidateCount=").append(candidateCount).append(" | ");
        sb.append("rankingMode=").append(rankingMode);
        sb.append(", rankingMs=").append(rankingMs);
        if (globalRerankMs > 0) {
            sb.append(" | globalRerankMs=").append(globalRerankMs);
            if (globalRerankPromptTokens > 0 || globalRerankTotalTokens > 0) {
                sb.append(", rerankPromptTokens=").append(globalRerankPromptTokens);
                sb.append(", rerankTotalTokens=").append(globalRerankTotalTokens);
            }
        }
        if (metadataEnrichMs > 0) {
            sb.append(" | metadataEnrichMs=").append(metadataEnrichMs);
        }
        sb.append(" | totalMs=").append(totalMs);
        sb.append(", resultCount=").append(resultCount);
        return sb.toString();
    }

    public List<String> toDebugDetails() {
        List<String> lines = new ArrayList<>();
        if (queryRewritten) {
            lines.add("query改写 | originalLength=" + originalQuery.length()
                    + ", processedLength=" + processedQuery.length());
        } else {
            lines.add("query未改写");
        }
        if (embeddingMs > 0) {
            lines.add("embedding | ms=" + embeddingMs
                    + (embeddingDim > 0 ? ", dim=" + embeddingDim : ""));
        }
        for (KbTrace kb : kbTraces) {
            StringBuilder kbLine = new StringBuilder();
            kbLine.append("库[").append(kb.knowledgeBaseId()).append("] ");
            kbLine.append("mode=").append(kb.retrievalMode());
            kbLine.append(", recallMs=").append(kb.recallMs());
            kbLine.append(", denseRaw=").append(kb.denseRaw());
            kbLine.append(", denseFiltered=").append(kb.denseFiltered());
            kbLine.append(", sparseRaw=").append(kb.sparseRaw());
            kbLine.append(", sparseTrimmed=").append(kb.sparseTrimmed());
            kbLine.append(", final=").append(kb.finalCount());
            if (kb.scoreThresholdEnabled()) {
                kbLine.append(", scoreThreshold=").append(kb.scoreThreshold());
            }
            if (kb.reranked()) {
                kbLine.append(", rerankMs=").append(kb.rerankMs());
            }
            lines.add(kbLine.toString());
        }
        lines.add("多库召回 | kbCount=" + recallKbCount
                + ", candidateCount=" + candidateCount
                + ", ms=" + recallMs);
        lines.add("请求级排序 | mode=" + rankingMode + ", ms=" + rankingMs);
        if (globalRerankMs > 0) {
            lines.add("全局rerank | ms=" + globalRerankMs
                    + ", promptTokens=" + globalRerankPromptTokens
                    + ", totalTokens=" + globalRerankTotalTokens);
        }
        if (metadataEnrichMs > 0) {
            lines.add("元数据补齐 | ms=" + metadataEnrichMs);
        }
        lines.add("总计 | totalMs=" + totalMs + ", resultCount=" + resultCount);
        return lines;
    }

    public boolean queryRewritten() { return queryRewritten; }
    public long embeddingMs() { return embeddingMs; }
    public long recallMs() { return recallMs; }
    public long rankingMs() { return rankingMs; }
    public long globalRerankMs() { return globalRerankMs; }
    public int globalRerankPromptTokens() { return globalRerankPromptTokens; }
    public int globalRerankTotalTokens() { return globalRerankTotalTokens; }
    public List<KbTrace> kbTraces() { return List.copyOf(kbTraces); }
}
