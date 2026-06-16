package com.cffex.rag.common.domain.metadata;

public record ModelQueryCondition(
        ModelType modelType,
        String modelName
) {
    public ModelQueryCondition {
        modelName = normalize(modelName);
    }

    public static ModelQueryCondition all() {
        return new ModelQueryCondition(null, null);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
