package com.gyeongsan.cabinet.domain.kakaonotify.model;

public class KakaoNotificationException extends RuntimeException {

    public KakaoNotificationException(String message) {
        super(message);
    }

    public KakaoNotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
