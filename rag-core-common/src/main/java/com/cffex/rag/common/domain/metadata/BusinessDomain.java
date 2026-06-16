package com.cffex.rag.common.domain.metadata;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record BusinessDomain(String code) {

    public static final BusinessDomain POLICY = new BusinessDomain("policy");
    public static final BusinessDomain RD_STANDARD = new BusinessDomain("rd-standard");
    public static final BusinessDomain RD_DATA = new BusinessDomain("rd-data");
    public static final BusinessDomain OPS_DATA = new BusinessDomain("ops-data");
    public static final BusinessDomain UNKNOWN = new BusinessDomain("unknown");

    private static final Map<String, BusinessDomain> PREDEFINED = Map.of(
            POLICY.code, POLICY,
            RD_STANDARD.code, RD_STANDARD,
            RD_DATA.code, RD_DATA,
            OPS_DATA.code, OPS_DATA,
            UNKNOWN.code, UNKNOWN
    );

    public BusinessDomain {
        code = normalize(code);
    }

    public static BusinessDomain fromCode(String code) {
        String normalized = normalize(code);
        return PREDEFINED.getOrDefault(normalized, new BusinessDomain(normalized));
    }

    private static String normalize(String code) {
        String normalized = Objects.requireNonNull(code, "code must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("business domain code must not be blank");
        }
        return normalized;
    }
}
