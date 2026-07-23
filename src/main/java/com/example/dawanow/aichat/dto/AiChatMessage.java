package com.example.dawanow.aichat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiChatMessage(
        @NotBlank
        @Pattern(regexp = "user|assistant", flags = Pattern.Flag.CASE_INSENSITIVE)
        String role,
        @NotBlank
        @Size(max = 4000)
        String content
) {
}
