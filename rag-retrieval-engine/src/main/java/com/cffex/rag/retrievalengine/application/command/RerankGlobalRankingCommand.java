package com.cffex.rag.retrievalengine.application.command;

import java.util.Objects;

import com.cffex.rag.retrievalengine.domain.model.GlobalRankingPolicy;
import com.cffex.rag.retrievalengine.domain.model.GlobalRerankPolicy;
import com.cffex.rag.retrievalengine.domain.model.RerankModelPolicy;

/**
 * 请求级模型重排命令。
 */
public record RerankGlobalRankingCommand(ModelEndpointCommand modelEndpoint)
        implements GlobalRankingCommand {

    public RerankGlobalRankingCommand {
        Objects.requireNonNull(modelEndpoint, "modelEndpoint must not be null");
    }

    @Override
    public GlobalRankingPolicy toPolicy(int topK, boolean scoreThresholdEnabled, double scoreThreshold) {
        return new GlobalRerankPolicy(
                new RerankModelPolicy(
                        modelEndpoint.endpoint(),
                        modelEndpoint.authToken(),
                        modelEndpoint.model()
                ),
                topK,
                scoreThresholdEnabled,
                scoreThreshold
        );
    }
}
