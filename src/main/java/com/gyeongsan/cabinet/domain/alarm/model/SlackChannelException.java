package com.gyeongsan.cabinet.domain.alarm.model;

public class SlackChannelException extends RuntimeException {

    public SlackChannelException(String message) {
        super(message);
    }

    public SlackChannelException(String message, Throwable cause) {
        super(message, cause);
    }
}
