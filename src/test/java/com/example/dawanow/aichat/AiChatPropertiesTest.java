package com.example.dawanow.aichat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AiChatPropertiesTest {

    @Test
    void exposesGatewayEndpointsWithoutChangingApplicationConfiguration() {
        AiChatProperties properties = properties(5000, 60000);

        assertEquals("https://gateway.test/api/v1/student/chat", properties.chatUrl());
        assertEquals("https://gateway.test/api/v1/student/multimodal-chat", properties.multimodalChatUrl());
    }

    @Test
    void rejectsInvalidTimeoutsAndModelIds() {
        assertThrows(IllegalArgumentException.class, () -> properties(0, 60000));
        assertThrows(IllegalArgumentException.class, () -> new AiChatProperties(
                "key", "https://gateway.test/api/v1", "invalid model id", "complex", "vision", "safe",
                false, false, 5000, 60000
        ));
    }

    private AiChatProperties properties(int connectTimeout, int readTimeout) {
        return new AiChatProperties(
                "key", "https://gateway.test/api/v1/", "haiku", "complex", "vision", "safe",
                false, false, connectTimeout, readTimeout
        );
    }
}
