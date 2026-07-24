package com.example.dawanow.aichat.dto;

import java.util.List;

public record AiChatResponse(
        String reply,
        String intent,
        String modelUsed,
        String providerStatus,
        List<AiChatCard> cards,
        List<Long> sourceProductIds,
        List<AiChatSuggestedAction> suggestedActions,
        boolean requiresConfirmation,
        String traceId
) {
}
