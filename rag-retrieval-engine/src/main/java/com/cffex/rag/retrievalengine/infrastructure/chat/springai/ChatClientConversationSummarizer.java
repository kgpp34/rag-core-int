package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.cffex.rag.retrievalengine.domain.model.ChatCompletionRequest;
import com.cffex.rag.retrievalengine.domain.model.ChatMessage;
import com.cffex.rag.retrievalengine.domain.model.ChatMessageRole;
import com.cffex.rag.retrievalengine.domain.model.ChatModelPolicy;

@Component
class ChatClientConversationSummarizer implements ConversationSummarizer {

    private static final String SYSTEM_PROMPT = """
            你负责压缩多轮对话上下文。请基于已有摘要和新增对话生成更新后的摘要。
            保留用户目标、关键事实、明确约束、已确认结论和仍未解决的问题。
            不要添加原文没有的信息，不要输出解释，只输出摘要正文。
            """;

    private final ChatClientFactory chatClientFactory;

    ChatClientConversationSummarizer(ChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    @Override
    public String summarize(ChatModelPolicy modelPolicy, String previousSummary,
            List<org.springframework.ai.chat.messages.Message> messages, int maxTokens) {
        String prompt = buildPrompt(previousSummary, messages);
        ChatCompletionRequest request = new ChatCompletionRequest(
                modelPolicy,
                List.of(new ChatMessage(ChatMessageRole.USER, prompt)),
                0.1d,
                maxTokens,
                null,
                null
        );
        String summary = chatClientFactory.create(request)
                .prompt()
                .messages(List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(prompt)))
                .call()
                .content();
        return summary == null ? "" : summary.trim();
    }

    private String buildPrompt(String previousSummary, List<org.springframework.ai.chat.messages.Message> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("已有摘要：\n");
        prompt.append(previousSummary == null || previousSummary.isBlank() ? "无" : previousSummary);
        prompt.append("\n\n新增对话：\n");
        for (org.springframework.ai.chat.messages.Message message : messages) {
            prompt.append(message.getMessageType()).append(": ").append(message.getText()).append('\n');
        }
        return prompt.toString();
    }
}
