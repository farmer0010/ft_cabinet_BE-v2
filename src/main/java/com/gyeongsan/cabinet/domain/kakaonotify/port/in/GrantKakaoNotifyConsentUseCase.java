package com.gyeongsan.cabinet.domain.kakaonotify.port.in;

import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotifyStatus;

public interface GrantKakaoNotifyConsentUseCase {

    KakaoNotifyStatus grantConsent(Long userId, String authorizationCode);
}
