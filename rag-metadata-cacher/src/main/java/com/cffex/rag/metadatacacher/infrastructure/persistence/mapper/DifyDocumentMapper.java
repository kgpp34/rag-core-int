package com.cffex.rag.metadatacacher.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface DifyDocumentMapper {

    @Select("""
            SELECT
                d.id AS document_id,
                d.dataset_id,
                d.name,
                d.data_source_info,
                uf.key AS upload_file_key
            FROM public.documents d
            LEFT JOIN public.upload_files uf
              ON uf.id::text = (d.data_source_info::jsonb ->> 'upload_file_id')
            WHERE d.enabled = TRUE
              AND d.archived = FALSE
            """)
    List<DocumentRow> scanAllDocuments();

    record DocumentRow(
            String documentId,
            String datasetId,
            String name,
            String dataSourceInfo,
            String uploadFileKey
    ) {}
}
