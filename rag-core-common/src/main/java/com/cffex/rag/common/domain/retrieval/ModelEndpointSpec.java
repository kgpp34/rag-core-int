package com.cffex.rag.common.domain.retrieval;

import java.util.Objects;

/**
 * 通用模型调用端点规格。
 *
 * <p>该对象只表达“调用一个远端模型所需的最小连接信息”，不绑定具体供应商协议。
 * 具体适配方式由基础设施层决定，例如可以映射到 OpenAI-compatible HTTP、厂商 SDK 等。
 */
public record ModelEndpointSpec(
        String endpoint,
        String authToken,
        String model
) {
    public ModelEndpointSpec {
        endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        authToken = Objects.requireNonNull(authToken, "authToken must not be null");
        model = Objects.requireNonNull(model, "model must not be null");
    }
}
