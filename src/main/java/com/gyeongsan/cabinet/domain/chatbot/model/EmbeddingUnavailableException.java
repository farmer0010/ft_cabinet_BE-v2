package com.gyeongsan.cabinet.domain.chatbot.model;

public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public EmbeddingUnavailableException(String message) {
        super(message);
    }
}
