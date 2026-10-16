package com.gyeongsan.cabinet.domain.kakaonotify.model;

public record KakaoRecipient(Long userId, String userName, String encryptedRefreshToken) {

    @Override
    public String toString() {
        return "KakaoRecipient[userId=" + userId + ", userName=" + userName + "]";
    }
}
