package com.example.dawanow.aichat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AiChatRequest(
        @Size(max = 20)
        List<@Valid AiChatMessage> history,
        @Size(max = 4000)
        String message,
        @Pattern(regexp = "en|ar", flags = Pattern.Flag.CASE_INSENSITIVE)
        String language,
        Double latitude,
        Double longitude
) {
}
