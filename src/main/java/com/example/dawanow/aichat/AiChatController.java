package com.example.dawanow.aichat;

import com.example.dawanow.aichat.dto.AiChatRequest;
import com.example.dawanow.aichat.dto.AiChatResponse;
import com.example.dawanow.aichat.dto.AiChatHistoryPage;
import com.example.dawanow.dtos.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/ai/chat")
@PreAuthorize("isAuthenticated()")
@Tag(name = "AI Chat", description = "Authenticated Medsy AI assistant with persistent per-user history")
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(
                            encoding = @Encoding(
                                    name = "payload",
                                    contentType = MediaType.APPLICATION_JSON_VALUE
                            )
                    )
            )
            @Parameter(description = "AI chat request JSON")
            @Valid @RequestPart("payload") AiChatRequest request,
            @RequestPart("image") MultipartFile image
    ) {
        return ResponseEntity.ok(
                ApiResponse.success("AI response generated", aiChatService.chat(request, image))
        );
    }

    @GetMapping("/history")
    @Operation(
            summary = "Get the current user's AI chat history",
            description = "Returns the authenticated customer or pharmacist's latest messages with cards and actions.",
            security = @SecurityRequirement(name = "basicAuth")
    )
    public ResponseEntity<ApiResponse<AiChatHistoryPage>> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(
                ApiResponse.success("AI chat history retrieved", aiChatService.history(page, size))
        );
    }

    @DeleteMapping("/history")
    @Operation(
            summary = "Clear the current user's AI chat history",
            security = @SecurityRequirement(name = "basicAuth")
    )
    public ResponseEntity<ApiResponse<Void>> clearHistory() {
        aiChatService.clearHistory();
        return ResponseEntity.ok(ApiResponse.success("AI chat history cleared"));
    }
}
