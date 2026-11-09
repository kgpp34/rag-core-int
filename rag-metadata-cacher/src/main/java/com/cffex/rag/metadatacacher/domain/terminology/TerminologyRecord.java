package com.cffex.rag.metadatacacher.domain.terminology;

/**
 * t_kb_terminology 表的一行原始记录，desc 列保留原始 JSON 文本，由快照工厂解析。
 */
public record TerminologyRecord(
        long id,
        String scope,
        String kbId,
        String term,
        String descJson
) {
}
