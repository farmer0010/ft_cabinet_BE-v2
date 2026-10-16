package com.gyeongsan.cabinet.domain.chatbot.model;

import java.time.LocalDateTime;
import java.util.List;

public record Faq(
        Long id,
        String seedKey,
        String category,
        String answer,
        boolean enabled,
        List<String> questions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public Faq {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    public String representativeQuestion() {
        return questions.isEmpty() ? "" : questions.get(0);
    }
}
