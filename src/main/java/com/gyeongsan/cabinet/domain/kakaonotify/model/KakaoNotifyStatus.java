package com.gyeongsan.cabinet.domain.kakaonotify.model;

public record KakaoNotifyStatus(boolean consented, boolean alarmEnabled) {

    public boolean receiving() {
        return consented && alarmEnabled;
    }
}
