package com.gyeongsan.cabinet.domain.kakaonotify.model;

public class NoticeSummaryException extends RuntimeException {

    public NoticeSummaryException(String message) {
        super(message);
    }

    public NoticeSummaryException(String message, Throwable cause) {
        super(message, cause);
    }
}
