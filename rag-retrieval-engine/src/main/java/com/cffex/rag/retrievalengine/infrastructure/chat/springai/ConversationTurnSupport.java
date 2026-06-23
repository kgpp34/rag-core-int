package com.cffex.rag.retrievalengine.infrastructure.chat.springai;

import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

final class ConversationTurnSupport {

    private ConversationTurnSupport() {
    }

    static int recentWindowStart(List<Message> history, int lowerBound, int windowTurns) {
        int safeLowerBound = Math.max(0, Math.min(lowerBound, history.size()));
        if (windowTurns <= 0) {
            return history.size();
        }
        int seenUserTurns = 0;
        for (int index = history.size() - 1; index >= safeLowerBound; index--) {
            Message message = history.get(index);
            if (message != null && message.getMessageType() == MessageType.USER) {
                seenUserTurns++;
                if (seenUserTurns == windowTurns) {
                    return index;
                }
            }
        }
        return safeLowerBound;
    }

    static int countUserTurns(List<Message> history, int fromInclusive, int toExclusive) {
        int from = Math.max(0, Math.min(fromInclusive, history.size()));
        int to = Math.max(from, Math.min(toExclusive, history.size()));
        int count = 0;
        for (int index = from; index < to; index++) {
            Message message = history.get(index);
            if (message != null && message.getMessageType() == MessageType.USER) {
                count++;
            }
        }
        return count;
    }
}
