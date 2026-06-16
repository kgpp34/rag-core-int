package com.cffex.rag.metadatacacher.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface DifyTagBindingMapper {

    @Select({
            "<script>",
            "SELECT",
            "    LOWER(BTRIM(t.name)) AS tag_code,",
            "    tb.target_id",
            "FROM public.tags t",
            "INNER JOIN public.tag_bindings tb ON tb.tag_id = t.id",
            "INNER JOIN public.datasets d ON d.id = tb.target_id",
            "WHERE t.type = 'knowledge'",
            "AND LOWER(BTRIM(t.name)) IN",
            "<foreach collection='tagCodes' item='tagCode' open='(' separator=',' close=')'>",
            "    #{tagCode}",
            "</foreach>",
            "</script>"
    })
    List<TagBindingRow> scanKnowledgeTagBindingsByTagCodes(@Param("tagCodes") List<String> tagCodes);

    record TagBindingRow(
            String tagCode,
            String datasetId
    ) {}
}
