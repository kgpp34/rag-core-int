package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.Optional;

public interface ConversationSummaryRepository {

    Optional<ConversationSummary> findByConversationId(String conversationId);

    void save(ConversationSummary summary);

    void deleteByConversationId(String conversationId);
}
