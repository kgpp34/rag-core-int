package com.cffex.rag.metadatacacher.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface DifyDocumentMapper {

    @Select("""
            SELECT
                id AS document_id,
                dataset_id,
                name,
                data_source_info
            FROM public.documents
            WHERE enabled = TRUE
              AND archived = FALSE
            """)
    List<DocumentRow> scanAllDocuments();

    record DocumentRow(
            String documentId,
            String datasetId,
            String name,
            String dataSourceInfo
    ) {}
}
