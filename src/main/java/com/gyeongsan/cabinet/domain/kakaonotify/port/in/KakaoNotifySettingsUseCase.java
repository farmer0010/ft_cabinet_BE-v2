package com.gyeongsan.cabinet.domain.kakaonotify.port.in;

import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotifyStatus;

public interface KakaoNotifySettingsUseCase {

    KakaoNotifyStatus getStatus(Long userId);

    KakaoNotifyStatus setAlarm(Long userId, boolean enabled);
}
