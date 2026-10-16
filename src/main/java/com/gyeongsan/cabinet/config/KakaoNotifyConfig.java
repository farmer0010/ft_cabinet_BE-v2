package com.gyeongsan.cabinet.config;

import com.gyeongsan.cabinet.adapter.out.crypto.AesGcmTokenCipher;
import com.gyeongsan.cabinet.application.kakaonotify.KakaoNotifyConsentService;
import com.gyeongsan.cabinet.application.kakaonotify.KakaoNotifySettingsService;
import com.gyeongsan.cabinet.domain.auth.port.out.LinkRedirectUriPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.in.GrantKakaoNotifyConsentUseCase;
import com.gyeongsan.cabinet.domain.kakaonotify.port.in.KakaoNotifySettingsUseCase;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoConsentRepositoryPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoLoginLinkPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoNotificationPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.TokenCipherPort;
import com.gyeongsan.cabinet.domain.user.port.out.UserRepositoryPort;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.kakao-notify.enabled", havingValue = "true")
public class KakaoNotifyConfig {

    @Bean
    public TokenCipherPort kakaoTokenCipher(
            @Value("${app.kakao-notify.enc-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "KAKAO_NOTIFY_ENABLED=true 이면 토큰 암호화 키(KAKAO_TOKEN_ENC_KEY, base64 32바이트)가 필요합니다.");
        }
        try {
            return new AesGcmTokenCipher(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("KAKAO_TOKEN_ENC_KEY 는 base64 로 인코딩한 32바이트 값이어야 합니다.");
        }
    }

    @Bean
    public GrantKakaoNotifyConsentUseCase grantKakaoNotifyConsentUseCase(
            KakaoLoginLinkPort loginLinkPort,
            LinkRedirectUriPort redirectUriPort,
            KakaoNotificationPort kakao,
            KakaoConsentRepositoryPort consentRepository,
            TokenCipherPort kakaoTokenCipher) {
        return new KakaoNotifyConsentService(
                loginLinkPort,
                redirectUriPort,
                kakao,
                consentRepository,
                kakaoTokenCipher,
                Clock.systemDefaultZone());
    }

    @Bean
    public KakaoNotifySettingsUseCase kakaoNotifySettingsUseCase(
            KakaoConsentRepositoryPort consentRepository, UserRepositoryPort userRepository) {
        return new KakaoNotifySettingsService(consentRepository, userRepository);
    }
}
