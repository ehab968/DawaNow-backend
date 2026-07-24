package com.example.dawanow.aichat.dto;

import java.util.List;

public record AiChatHistoryPage(
        List<AiChatHistoryItem> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean last
) {
}
