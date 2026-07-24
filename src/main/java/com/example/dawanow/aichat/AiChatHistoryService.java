package com.example.dawanow.aichat;

import com.example.dawanow.aichat.dto.AiChatHistoryItem;
import com.example.dawanow.aichat.dto.AiChatHistoryPage;
import com.example.dawanow.aichat.dto.AiChatRequest;
import com.example.dawanow.aichat.dto.AiChatResponse;
import com.example.dawanow.entity.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiChatHistoryService {

    private final AiChatHistoryRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiChatHistoryService(AiChatHistoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void saveExchange(
            User user,
            AiChatRequest request,
            boolean hasImage,
            AiChatResponse response
    ) {
        LocalDateTime now = LocalDateTime.now();

        AiChatHistoryMessage userMessage = new AiChatHistoryMessage();
        userMessage.setUser(user);
        userMessage.setRole("user");
        userMessage.setContent(request.message() == null ? "" : request.message());
        userMessage.setHasImage(hasImage);
        userMessage.setCreatedAt(now);

        AiChatHistoryMessage assistantMessage = new AiChatHistoryMessage();
        assistantMessage.setUser(user);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(response.reply());
        assistantMessage.setHasImage(false);
        assistantMessage.setResponseJson(serialize(response));
        assistantMessage.setCreatedAt(now.plusNanos(1));

        repository.saveAll(List.of(userMessage, assistantMessage));
    }

    @Transactional(readOnly = true)
    public AiChatHistoryPage history(User user, int requestedPage, int requestedSize) {
        int pageNumber = Math.max(requestedPage, 0);
        int pageSize = Math.min(Math.max(requestedSize, 1), 100);
        Page<AiChatHistoryMessage> page = repository.findByUserIdOrderByCreatedAtDesc(
                user.getId(),
                PageRequest.of(pageNumber, pageSize)
        );

        List<AiChatHistoryMessage> chronological = new ArrayList<>(page.getContent());
        Collections.reverse(chronological);
        return new AiChatHistoryPage(
                chronological.stream().map(this::map).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }

    @Transactional
    public void clear(User user) {
        repository.deleteByUserId(user.getId());
    }

    private AiChatHistoryItem map(AiChatHistoryMessage message) {
        AiChatResponse response = deserialize(message.getResponseJson());
        return new AiChatHistoryItem(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.isHasImage(),
                message.getCreatedAt(),
                response == null ? List.of() : response.cards(),
                response == null ? List.of() : response.suggestedActions()
        );
    }

    private String serialize(AiChatResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI chat response could not be stored", exception);
        }
    }

    private AiChatResponse deserialize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, AiChatResponse.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
