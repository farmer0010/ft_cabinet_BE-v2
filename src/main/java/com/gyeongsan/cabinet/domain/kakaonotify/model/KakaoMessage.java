package com.gyeongsan.cabinet.domain.kakaonotify.model;

public record KakaoMessage(String text, String linkUrl) {

    public static final int MAX_TEXT_LENGTH = 200;
}
