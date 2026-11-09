package com.cffex.rag.retrievalengine.infrastructure.vectorstore.kingbase;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.cffex.rag.common.exception.RagErrorCode;
import com.cffex.rag.common.exception.RagServiceException;
import com.cffex.rag.retrievalengine.config.KingbaseProperties;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Kingbase 检索共享支持：SQL 拼装、候选映射与阈值过滤。
 *
 * <p>数据表沿用 Dify 写入 Kingbase 的约定，即 {@code <table-prefix><collectionName>}，
 * 列固定为 {@code id / text / meta / embedding}：
 * <ul>
 *   <li>{@code id}       主键，同时也是 Dify 的分段索引节点 ID（metadata 里的 {@code doc_id}）</li>
 *   <li>{@code text}     分段正文</li>
 *   <li>{@code meta}     JSONB 元数据，含 {@code document_id}、{@code doc_id}、{@code dataset_id}</li>
 *   <li>{@code embedding} KES_Vector 的 {@code vector(n)} 列</li>
 * </ul>
 *
 * <p>表名与全文检索配置名会拼进 SQL，因此只接受合法标识符，避免元数据被污染后形成注入。
 */
@Component
public class KingbaseSearchSupport {

    static final String DOCUMENT_ID_METADATA_KEY = "document_id";
    static final String CHUNK_ID_METADATA_KEY = "doc_id";
    static final String KNOWLEDGE_BASE_ID_METADATA_KEY = "knowledge_base_id";

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final KingbaseProperties properties;
    private final ObjectMapper objectMapper;

    public KingbaseSearchSupport(KingbaseProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        requireIdentifier(properties.tablePrefix(), "kingbase.table-prefix");
    }

    /** 把 collection 名称解析成实际数据表名。 */
    public String tableName(String collectionName) {
        Objects.requireNonNull(collectionName, "collectionName must not be null");
        if (!SQL_IDENTIFIER.matcher(collectionName).matches()) {
            throw KingbaseExceptionSupport.invalidCollectionName(collectionName);
        }
        return properties.tablePrefix() + collectionName;
    }

    /** 当前生效的全文检索配置名，未配置时说明全文检索被关闭。 */
    public String ftsConfig() {
        String ftsConfig = properties.ftsConfig();
        if (ftsConfig == null || ftsConfig.isBlank()) {
            throw new RagServiceException(
                    RagErrorCode.INVALID_CONFIGURATION,
                    "Kingbase 全文检索未启用：请配置 kingbase.fts-config（英文 simple，中文 jiebacfg）"
            );
        }
        return requireIdentifier(ftsConfig, "kingbase.fts-config");
    }

    public boolean fullTextEnabled() {
        String ftsConfig = properties.ftsConfig();
        return ftsConfig != null && !ftsConfig.isBlank();
    }

    /** 把 float 向量渲染成 KES_Vector 的文本字面量，例如 {@code [0.1,0.2]}。 */
    public String formatVector(float[] vector) {
        Objects.requireNonNull(vector, "vector must not be null");
        StringBuilder builder = new StringBuilder(vector.length * 12 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }

    public String buildDenseSql(String tableName, List<String> docIds, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, text, meta, (embedding <=> ?::vector) AS distance FROM ")
                .append(tableName);
        appendDocIdFilter(sql, docIds, "WHERE");
        return sql.append(" ORDER BY distance NULLS LAST LIMIT ").append(requireLimit(limit)).toString();
    }

    public String buildFullTextSql(String tableName, List<String> docIds, int limit) {
        String ftsConfig = ftsConfig();
        // plainto_tsquery 生成的是 AND 语义（'a' & 'b' & ...），中文问句切出十来个词后几乎必然 0 命中；
        // 这里改写成 OR 语义（命中任意词即可召回），由 ts_rank 负责排序。
        String tsQuery = "to_tsquery('" + ftsConfig + "', replace(plainto_tsquery('" + ftsConfig
                + "', ?)::text, ' & ', ' | '))";
        StringBuilder sql = new StringBuilder("SELECT id, text, meta, ts_rank(to_tsvector('")
                .append(ftsConfig)
                .append("', text), ")
                .append(tsQuery)
                .append(") AS score FROM ")
                .append(tableName)
                .append(" WHERE to_tsvector('")
                .append(ftsConfig)
                .append("', text) @@ ")
                .append(tsQuery);
        appendDocIdFilter(sql, docIds, "AND");
        return sql.append(" ORDER BY score DESC LIMIT ").append(requireLimit(limit)).toString();
    }

    public RowMapper<RetrievalCandidate> denseRowMapper(String knowledgeBaseId) {
        return (resultSet, rowNum) -> toCandidate(resultSet, knowledgeBaseId, true);
    }

    public RowMapper<RetrievalCandidate> fullTextRowMapper(String knowledgeBaseId) {
        return (resultSet, rowNum) -> toCandidate(resultSet, knowledgeBaseId, false);
    }

    public List<RetrievalCandidate> filterDenseByScoreThreshold(
            List<RetrievalCandidate> candidates,
            boolean enabled,
            double threshold
    ) {
        if (!enabled) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> candidate.finalScore() > threshold)
                .toList();
    }

    public List<RetrievalCandidate> filterFullTextByScoreThreshold(
            List<RetrievalCandidate> candidates,
            boolean enabled,
            double threshold
    ) {
        if (!enabled) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> Objects.requireNonNullElse(candidate.sparseScore(), 0.0d) > threshold)
                .toList();
    }

    private static void appendDocIdFilter(StringBuilder sql, List<String> docIds, String leadingKeyword) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        String placeholders = docIds.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        sql.append(' ').append(leadingKeyword).append(" meta->>'").append(DOCUMENT_ID_METADATA_KEY).append("' IN (")
                .append(placeholders).append(')');
    }

    private static int requireLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive but was " + limit);
        }
        return limit;
    }

    private static String requireIdentifier(String value, String property) {
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
            throw new RagServiceException(
                    RagErrorCode.INVALID_CONFIGURATION,
                    "非法的 %s 配置值（只允许字母/数字/下划线）：%s".formatted(property, value)
            );
        }
        return value;
    }

    private RetrievalCandidate toCandidate(ResultSet resultSet, String knowledgeBaseId, boolean dense)
            throws SQLException {
        Map<String, Object> metadata = parseMetadata(resultSet.getString("meta"));
        metadata.putIfAbsent(KNOWLEDGE_BASE_ID_METADATA_KEY, knowledgeBaseId);
        String primaryKey = Objects.toString(resultSet.getString("id"), "");
        // Dify 落库时主键就是分段索引节点 ID，缺失时回退到主键，保证引用链路有值可用
        String chunkId = Objects.toString(metadata.get(CHUNK_ID_METADATA_KEY), primaryKey);
        metadata.putIfAbsent(CHUNK_ID_METADATA_KEY, chunkId);
        // 余弦距离越小越相似，转成和 Milvus 一致的“越大越相似”的分数
        double rawScore = resultSet.getDouble(dense ? "distance" : "score");
        double score = dense ? 1.0d - rawScore : rawScore;
        return new RetrievalCandidate(
                chunkId,
                Objects.toString(metadata.get(DOCUMENT_ID_METADATA_KEY), ""),
                knowledgeBaseId,
                Objects.toString(resultSet.getString("text"), ""),
                dense ? score : 0.0d,
                dense ? null : score,
                metadata
        );
    }

    private Map<String, Object> parseMetadata(String payload) {
        if (payload == null || payload.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(payload, METADATA_TYPE));
        } catch (JsonProcessingException ex) {
            throw KingbaseExceptionSupport.metadataParseFailed(payload, ex);
        }
    }

    /** 供调试 trace 描述一次 dense 查询。 */
    public Map<String, Object> describeDenseQuery(String tableName, List<String> docIds, int limit, float[] vector) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("table", tableName);
        description.put("docIdCount", docIds.size());
        description.put("limit", limit);
        description.put("queryVectorDim", vector.length);
        description.put("metric", "cosine");
        return description;
    }

    /** 供调试 trace 描述一次全文查询。 */
    public Map<String, Object> describeFullTextQuery(String tableName, List<String> docIds, int limit) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("table", tableName);
        description.put("docIdCount", docIds.size());
        description.put("limit", limit);
        description.put("ftsConfig", properties.ftsConfig());
        return description;
    }

    /** 把 docIds 平铺成 SQL 参数（配合 {@link #appendDocIdFilter} 的顺序）。 */
    public static List<Object> docIdArguments(List<String> docIds) {
        return docIds == null ? List.of() : new ArrayList<>(docIds);
    }
}
