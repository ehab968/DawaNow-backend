package com.example.dawanow.aichat;

public class AiChatProviderException extends RuntimeException {

    private final String code;

    public AiChatProviderException(String message) {
        this("PROVIDER_ERROR", message, null);
    }

    public AiChatProviderException(String message, Throwable cause) {
        this("PROVIDER_ERROR", message, cause);
    }

    public AiChatProviderException(String code, String message) {
        this(code, message, null);
    }

    public AiChatProviderException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
