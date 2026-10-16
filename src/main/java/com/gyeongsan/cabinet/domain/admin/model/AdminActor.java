package com.gyeongsan.cabinet.domain.admin.model;

public record AdminActor(Long id, String name) {

    public AdminActor {
        if (id == null) {
            throw new IllegalArgumentException("관리자 ID 는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("관리자 이름은 필수입니다.");
        }
    }
}
