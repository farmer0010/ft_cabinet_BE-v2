package com.gyeongsan.cabinet.domain.kakaonotify.model;

public class KakaoInsufficientScopeException extends RuntimeException {

    public KakaoInsufficientScopeException(String message) {
        super(message);
    }
}
