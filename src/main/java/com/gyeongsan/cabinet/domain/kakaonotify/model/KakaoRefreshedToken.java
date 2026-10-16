package com.gyeongsan.cabinet.domain.kakaonotify.model;

public record KakaoRefreshedToken(String accessToken, String newRefreshToken) {

    @Override
    public String toString() {
        return "KakaoRefreshedToken[rotated=" + (newRefreshToken != null) + "]";
    }
}
