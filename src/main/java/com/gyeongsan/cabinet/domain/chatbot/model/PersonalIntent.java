package com.gyeongsan.cabinet.domain.chatbot.model;

import java.util.Optional;

public enum PersonalIntent {
    LENT_EXPIRY("내 사물함 만료일 확인"),
    PENALTY_STATUS("내 패널티 확인"),
    LENT_TICKET_CONDITION("대여권 지급 조건 확인"),
    LENT_BLOCKER_DIAGNOSIS("대여가 안 되는 이유 확인");

    private final String label;

    PersonalIntent(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<PersonalIntent> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (PersonalIntent intent : values()) {
            if (intent.name().equals(name.trim())) {
                return Optional.of(intent);
            }
        }
        return Optional.empty();
    }
}
