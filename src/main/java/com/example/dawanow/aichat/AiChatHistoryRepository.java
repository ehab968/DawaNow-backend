package com.example.dawanow.aichat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiChatHistoryRepository extends JpaRepository<AiChatHistoryMessage, Long> {
    Page<AiChatHistoryMessage> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    void deleteByUserId(Long userId);
}
