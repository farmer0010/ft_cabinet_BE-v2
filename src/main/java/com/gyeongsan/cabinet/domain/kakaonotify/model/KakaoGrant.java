package com.gyeongsan.cabinet.domain.kakaonotify.model;

import java.util.Set;

public record KakaoGrant(String providerId, String refreshToken, Set<String> scopes) {

    public KakaoGrant {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    @Override
    public String toString() {
        return "KakaoGrant[providerId=" + providerId + ", scopes=" + scopes + "]";
    }
}
