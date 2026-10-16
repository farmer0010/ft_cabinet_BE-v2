package com.gyeongsan.cabinet.domain.kakaonotify.port.out;

import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoGrant;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoMessage;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoRefreshedToken;

public interface KakaoNotificationPort {

    KakaoGrant exchangeAuthorizationCode(String authorizationCode, String redirectUri);

    KakaoRefreshedToken refreshAccessToken(String refreshToken);

    void sendToMe(String accessToken, KakaoMessage message);
}
