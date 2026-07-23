package com.example.dawanow.aichat;

import com.example.dawanow.aichat.dto.AiChatRequest;
import com.example.dawanow.aichat.dto.AiChatResponse;
import com.example.dawanow.dtos.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/ai/chat")
@PreAuthorize("isAuthenticated()")
@Tag(name = "AI Chat", description = "Session-only Medsy AI assistant")
public class AiChatController {

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Chat with the Medsy assistant",
            description = "Uses bounded client-provided session history and read-only Medsy tools.",
            security = @SecurityRequirement(name = "basicAuth")
    )
    public ResponseEntity<ApiResponse<AiChatResponse>> chat(
            @Valid @RequestBody AiChatRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success("AI response generated", aiChatService.chat(request)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Chat with an attached prescription or medicine image",
            description = "Accepts an application/json payload part and a JPEG or PNG image part.",
            security = @SecurityRequirement(name = "basicAuth")
    )
    public ResponseEntity<ApiResponse<AiChatResponse>> chatWithImage(
            @Valid @RequestPart("payload") AiChatRequest request,
            @RequestPart("image") MultipartFile image
    ) {
        return ResponseEntity.ok(
                ApiResponse.success("AI response generated", aiChatService.chat(request, image))
        );
    }
}
