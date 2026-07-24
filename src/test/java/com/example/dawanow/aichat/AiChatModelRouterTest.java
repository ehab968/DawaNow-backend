package com.example.dawanow.aichat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiChatModelRouterTest {

    private AiChatProperties properties;
    private AiChatModelRouter router;

    @BeforeEach
    void setUp() {
        properties = new AiChatProperties(
                "test-key",
                "http://apiaccess.iti.net.eg/api/v1",
                "anthropic.claude-haiku-4-5-20251001-v1:0",
                "openai.gpt-oss-20b-1:0",
                "qwen.qwen3-vl-235b-a22b",
                "openai.gpt-oss-safeguard-20b",
                false,
                false,
                5000,
                60000
        );
        router = new AiChatModelRouter(properties);
    }

    @Test
    void routesProductSearchToHaiku() {
        AiChatRoute route = router.route("Find Panadol", false);

        assertEquals(AiChatIntent.PRODUCT_SEARCH, route.intent());
        assertEquals(properties.haikuModel(), route.modelId());
    }

    @Test
    void routesMedicalQuestionToLowCostComplexModel() {
        AiChatRoute route = router.route("I have a headache and feel dizzy", false);

        assertEquals(AiChatIntent.MEDICAL_INFORMATION, route.intent());
        assertEquals(properties.complexModel(), route.modelId());
        assertTrue(route.safetyRelevant());
    }

    @Test
    void routesEveryImageToVisionModel() {
        AiChatRoute route = router.route("What medicine is this?", true);

        assertEquals(AiChatIntent.IMAGE_ANALYSIS, route.intent());
        assertEquals(properties.visionModel(), route.modelId());
    }

    @Test
    void detectsEmergencyPhrasesLocally() {
        assertTrue(router.isEmergency("I have chest pain and cannot breathe"));
        assertTrue(router.isEmergency("مش قادر اتنفس"));
    }
}
