package com.example.dawanow.aichat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AiChatProperties {

    private final String apiKey;
    private final String gatewayBaseUrl;
    private final String haikuModel;
    private final String complexModel;
    private final String visionModel;
    private final String safeguardModel;
    private final boolean safetyEnabled;
    private final boolean medicalPrototypeEnabled;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public AiChatProperties(
            @Value("${SBG_API_KEY:}") String apiKey,
            @Value("${AI_CHAT_GATEWAY_BASE_URL:http://apiaccess.iti.net.eg/api/v1}") String gatewayBaseUrl,
            @Value("${AI_CHAT_MODEL_HAIKU:us.meta.llama3-3-70b-instruct-v1:0}") String haikuModel,
            @Value("${AI_CHAT_MODEL_COMPLEX:openai.gpt-oss-20b-1:0}") String complexModel,
            @Value("${AI_CHAT_MODEL_VISION:qwen.qwen3-vl-235b-a22b}") String visionModel,
            @Value("${AI_CHAT_MODEL_SAFEGUARD:openai.gpt-oss-safeguard-20b}") String safeguardModel,
            @Value("${AI_CHAT_SAFETY_ENABLED:true}") boolean safetyEnabled,
            @Value("${AI_MEDICAL_PROTOTYPE_ENABLED:false}") boolean medicalPrototypeEnabled,
            @Value("${AI_CHAT_CONNECT_TIMEOUT_MS:5000}") int connectTimeoutMs,
            @Value("${AI_CHAT_READ_TIMEOUT_MS:60000}") int readTimeoutMs
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.gatewayBaseUrl = stripTrailingSlash(gatewayBaseUrl);
        this.haikuModel = requireModelId(haikuModel, "Haiku");
        this.complexModel = requireModelId(complexModel, "complex text");
        this.visionModel = requireModelId(visionModel, "vision");
        this.safeguardModel = requireModelId(safeguardModel, "safeguard");
        this.safetyEnabled = safetyEnabled;
        this.medicalPrototypeEnabled = medicalPrototypeEnabled;
        this.connectTimeoutMs = positiveTimeout(connectTimeoutMs, "connect");
        this.readTimeoutMs = positiveTimeout(readTimeoutMs, "read");
    }

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }

    public String apiKey() {
        return apiKey;
    }

    public String chatUrl() {
        return gatewayBaseUrl + "/student/chat";
    }

    public String multimodalChatUrl() {
        return gatewayBaseUrl + "/student/multimodal-chat";
    }

    public String haikuModel() {
        return haikuModel;
    }

    public String complexModel() {
        return complexModel;
    }

    public String visionModel() {
        return visionModel;
    }

    public String safeguardModel() {
        return safeguardModel;
    }

    public boolean safetyEnabled() {
        return safetyEnabled;
    }

    public boolean medicalPrototypeEnabled() {
        return medicalPrototypeEnabled;
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    private static String stripTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("AI chat gateway base URL is required");
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String requireModelId(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,150}")) {
            throw new IllegalArgumentException("Invalid " + label + " AI model ID");
        }
        return value;
    }

    private static int positiveTimeout(int value, String label) {
        if (value <= 0) {
            throw new IllegalArgumentException("AI chat " + label + " timeout must be positive");
        }
        return value;
    }
}
