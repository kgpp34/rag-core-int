package com.cffex.rag.metadatacacher.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface DifySegmentMapper {

    @Select("""
            SELECT
                s.index_node_id,
                s.document_id,
                s.position
            FROM public.document_segments s
            WHERE s.index_node_id IS NOT NULL
              AND s.index_node_id <> ''
            """)
    List<SegmentRow> scanAllSegments();

    record SegmentRow(
            String indexNodeId,
            String documentId,
            int position
    ) {}
}
