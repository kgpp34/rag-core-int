package com.cffex.rag.common.domain.metadata;

/**
 * Dify {@code document_segments} 分段元数据。
 *
 * @param indexNodeId 分段索引节点 ID（Milvus metadata JSON 中的 {@code doc_id}）
 * @param documentId  分段所属文档 ID
 * @param position    分段在文档内的序号（从 0 开始）
 */
public record SegmentMeta(
        String indexNodeId,
        String documentId,
        int position
) {
}
