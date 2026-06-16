package com.cffex.rag.retrievalengine.application.command;

import java.util.Map;
import java.util.Objects;

/**
 * 检索绑定的内部命令对象。
 */
public record SearchBindingCommand(
        String engineId,
        String targetName,
        Map<String, Object> options
) {

    public SearchBindingCommand {
        Objects.requireNonNull(engineId, "engineId must not be null");
        Objects.requireNonNull(targetName, "targetName must not be null");
        options = Map.copyOf(options == null ? Map.of() : options);
    }
}
