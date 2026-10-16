package com.gyeongsan.cabinet.adapter.in.web.kakaonotify.dto;

import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotifyStatus;

public record KakaoNotifyStatusResponse(
        boolean consented, boolean alarmEnabled, boolean receiving) {

    public static KakaoNotifyStatusResponse from(KakaoNotifyStatus status) {
        return new KakaoNotifyStatusResponse(
                status.consented(), status.alarmEnabled(), status.receiving());
    }
}
