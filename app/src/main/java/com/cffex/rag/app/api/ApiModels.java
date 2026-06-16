package com.cffex.rag.app.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.cffex.rag.common.domain.query.PlanType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public final class ApiModels {

    private ApiModels() {
    }

    @Schema(description = "检索参数调优配置")
    public record RetrievalTuning(
            @Schema(description = "最终召回条数", example = "5")
            Integer topK,
            @Schema(description = "粗召回候选数", example = "20")
            Integer candidateK,
            @Schema(description = "分数阈值", example = "0.6")
            Double scoreThreshold
    ) {}

    @Schema(description = "多轮对话记忆配置")
    public record MemoryConfig(
            @NotBlank
            @Schema(description = "会话标识，由客户端生成，同一会话使用相同 UUID 以延续上下文", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            String conversationId
    ) {}

    @Schema(description = "查询改写配置")
    public record QueryRewriteConfig(
            @Schema(description = "是否启用查询改写", example = "true")
            Boolean enabled,
            @Schema(description = "改写提示词")
            String prompt
    ) {}

    @Schema(description = "检索请求")
    public record QueryRequest(
            @NotBlank
            @Schema(description = "用户查询", example = "交易所关于程序化交易的风控要求有哪些？")
            String query,
            @Schema(description = "指定文档 ID 列表", example = "[\"doc-1\",\"doc-2\"]")
            List<String> docIds,
            @Schema(description = "检索方案类型")
            PlanType planType,
            @Schema(description = "覆盖默认 system prompt")
            String systemPrompt,
            RetrievalTuning retrievalTuning,
            QueryRewriteConfig queryRewrite
    ) {
        public QueryRequest(
                String query,
                List<String> docIds,
                PlanType planType,
                String systemPrompt
        ) {
            this(query, docIds, planType, systemPrompt, null, null);
        }
    }

    @Schema(description = "RAG 问答请求")
    public record RagAnswerRequest(
            @NotBlank
            @Schema(description = "用户问题", example = "请总结程序化交易异常报送流程。")
            String query,
            @NotBlank
            @Schema(description = "用户标识", example = "user-001")
            String userId,
            @NotEmpty
            @Schema(description = "指定文档 ID 列表", example = "[\"doc-1\",\"doc-2\"]")
            List<String> docIds,
            @Schema(description = "检索方案类型")
            PlanType planType,
            @Schema(description = "覆盖默认 system prompt")
            String systemPrompt,
            RetrievalTuning retrievalTuning,
            @Schema(description = "对话记忆配置；上下文窗口由服务端自动管理")
            MemoryConfig memory,
            QueryRewriteConfig queryRewrite
    ) {
        public RagAnswerRequest(
                String query,
                List<String> docIds,
                PlanType planType,
                String systemPrompt
        ) {
            this(query, null, docIds, planType, systemPrompt, null, null, null);
        }
    }

    @Schema(description = "检索结果")
    public record RetrievalResponse(
            @Schema(description = "请求追踪 ID", example = "req-20260526-0001")
            String requestId,
            List<RetrievedChunkView> chunks,
            @Schema(description = "调试信息")
            Map<String, Object> debugTrace
    ) {}

    @Schema(description = "召回片段视图")
    public record RetrievedChunkView(
            String chunkId,
            String documentId,
            String knowledgeBaseId,
            double vectorScore,
            Double sparseScore,
            double rankingScore,
            String content,
            Map<String, Object> metadata
    ) {}

    @Schema(description = "引用文件")
    public record Reference(
            @Schema(description = "文件名", example = "程序化交易管理办法.pdf")
            String fileName,
            @Schema(description = "文件访问路径", example = "https://example.com/files/doc-1")
            String path
    ) {}

    @Schema(description = "RAG 问答响应")
    public record RagAnswerResponse(
            @Schema(description = "回答内容")
            String answer,
            List<Reference> references
    ) {}

    @Schema(description = "流式增量内容")
    public record RagAnswerStreamDelta(String content) {}

    @Schema(description = "RAG 流程进度事件")
    public record RagProgressEvent(
            String eventId,
            long sequence,
            String traceId,
            Instant occurredAt,
            String stage,
            String status,
            String title,
            Long elapsedMs,
            Map<String, Object> details
    ) {}

    @Schema(description = "流式结束事件")
    public record RagAnswerStreamDone(
            String finishReason,
            Map<String, Object> metadata
    ) {}

    @Schema(description = "流式错误事件")
    public record RagAnswerStreamError(
            String code,
            String message
    ) {}

    @Schema(description = "统一错误响应")
    public record ErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String code,
            String message,
            String path,
            String traceId,
            boolean retryable
    ) {}
}
