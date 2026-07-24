package com.example.dawanow.aichat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AiChatControllerSecurityTest {

    @Test
    void chatbotControllerExplicitlyRequiresAuthentication() {
        PreAuthorize authorization = AiChatController.class.getAnnotation(PreAuthorize.class);

        assertNotNull(authorization);
        assertEquals("isAuthenticated()", authorization.value());
    }
}
