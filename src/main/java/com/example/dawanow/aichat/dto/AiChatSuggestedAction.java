package com.example.dawanow.aichat.dto;

import java.util.Map;

public record AiChatSuggestedAction(
        String type,
        String label,
        Map<String, Object> payload,
        boolean requiresConfirmation
) {
}
