package com.cffex.rag.retrievalengine.domain;

/**
 * 存储检索请求的密封基类型。
 *
 * <p>每个子类型只携带自身检索模式需要的字段，避免“一个大对象塞所有字段”。
 *
 * <p>借助 sealed + switch，适配器可做穷尽分派，新增检索模式时编译期可提示未覆盖分支。
 */
public sealed interface StorageSearchRequest
        permits DenseVectorSearchRequest, FullTextSearchRequest {

    String engineId();
}
