package com.example.dawanow.aichat.dto;

import java.util.Map;

public record AiChatCard(
        String type,
        String title,
        String subtitle,
        Map<String, Object> data
) {
}
