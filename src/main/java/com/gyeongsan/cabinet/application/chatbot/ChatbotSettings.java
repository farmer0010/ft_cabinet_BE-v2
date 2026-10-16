package com.gyeongsan.cabinet.application.chatbot;

public record ChatbotSettings(
        double matchThreshold,
        double suggestThreshold,
        int maxSuggestions,
        double matchMargin,
        int matchAlternatives,
        int maxQuestionLength,
        int maxConcurrentEmbeddings,
        String fallbackMessage) {

    public ChatbotSettings {
        if (suggestThreshold <= 0 || suggestThreshold > matchThreshold || matchThreshold > 1) {
            throw new IllegalArgumentException(
                    "챗봇 임계값은 0 < suggest-threshold <= match-threshold <= 1 이어야 합니다.");
        }
        if (matchMargin < 0 || matchMargin >= 1) {
            throw new IllegalArgumentException("챗봇 match-margin 은 0 이상 1 미만이어야 합니다.");
        }
        if (matchAlternatives < 0) {
            throw new IllegalArgumentException("챗봇 match-alternatives 는 0 이상이어야 합니다.");
        }
        if (maxSuggestions < 0 || maxQuestionLength <= 0 || maxConcurrentEmbeddings <= 0) {
            throw new IllegalArgumentException("챗봇 한도 설정은 양수여야 합니다.");
        }
        if (fallbackMessage == null || fallbackMessage.isBlank()) {
            throw new IllegalArgumentException("챗봇 안내 문구(fallback-message)가 필요합니다.");
        }
    }

    public ChatbotSettings(
            double matchThreshold,
            double suggestThreshold,
            int maxSuggestions,
            int maxQuestionLength,
            int maxConcurrentEmbeddings,
            String fallbackMessage) {
        this(
                matchThreshold,
                suggestThreshold,
                maxSuggestions,
                0,
                0,
                maxQuestionLength,
                maxConcurrentEmbeddings,
                fallbackMessage);
    }
}
