package com.gyeongsan.cabinet.domain.chatbot.model;

import java.util.List;

public record ChatbotAnswer(
        Result result,
        Faq faq,
        String matchedQuestion,
        double score,
        List<Suggestion> suggestions,
        PersonalIntent personalAction) {

    public ChatbotAnswer(
            Result result,
            Faq faq,
            String matchedQuestion,
            double score,
            List<Suggestion> suggestions) {
        this(result, faq, matchedQuestion, score, suggestions, null);
    }

    public ChatbotAnswer withPersonalAction(PersonalIntent action) {
        return new ChatbotAnswer(result, faq, matchedQuestion, score, suggestions, action);
    }

    public enum Result {
        MATCHED,
        SUGGESTED,
        UNMATCHED
    }

    public record Suggestion(long faqId, String question, double score) {}

    public static ChatbotAnswer unmatched(double bestScore) {
        return new ChatbotAnswer(Result.UNMATCHED, null, null, bestScore, List.of());
    }
}
