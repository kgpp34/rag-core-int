package com.cffex.rag.metadatacacher.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface DifyProviderModelMapper {

    @Select("""
            SELECT
                id AS model_id,
                tenant_id::text AS tenant_id,
                provider_name,
                model_name,
                model_type,
                encrypted_config,
                COALESCE(is_valid, FALSE) AS is_valid
            FROM public.provider_models
            WHERE tenant_id::text = #{tenantId}
            """)
    List<ProviderModelRow> scanAllProviderModels(@Param("tenantId") String tenantId);

    record ProviderModelRow(
            String modelId,
            String tenantId,
            String providerName,
            String modelName,
            String modelType,
            String encryptedConfig,
            boolean isValid
    ) {}
}
