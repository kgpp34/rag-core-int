package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

public interface ConversationSummarizer {

    String summarize(ChatModelPolicy modelPolicy, String previousSummary, List<Message> messages, int maxTokens);
}
