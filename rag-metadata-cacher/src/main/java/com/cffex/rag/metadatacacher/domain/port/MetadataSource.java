package com.cffex.rag.metadatacacher.domain.port;

import com.cffex.rag.metadatacacher.domain.MetadataPayload;

/**
 * 元数据源出站端口。
 *
 * <p>每次调用都应返回当前刷新周期下该数据源拥有的完整元数据集合。
 * 实现类返回的 Map 需要已经是不可变对象。
 */
public interface MetadataSource {

    /** 返回用于日志和监控的可读数据源名称。 */
    String sourceName();

    /** 执行一次完整元数据加载。 */
    MetadataPayload load();
}
