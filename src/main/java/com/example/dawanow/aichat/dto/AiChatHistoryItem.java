package com.example.dawanow.aichat.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AiChatHistoryItem(
        Long id,
        String role,
        String content,
        boolean hasImage,
        LocalDateTime createdAt,
        List<AiChatCard> cards,
        List<AiChatSuggestedAction> suggestedActions
) {
}
