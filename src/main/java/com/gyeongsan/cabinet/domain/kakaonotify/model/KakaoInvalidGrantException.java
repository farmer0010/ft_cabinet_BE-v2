package com.gyeongsan.cabinet.domain.kakaonotify.model;

public class KakaoInvalidGrantException extends RuntimeException {

    public KakaoInvalidGrantException(String message) {
        super(message);
    }
}
