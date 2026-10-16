package com.gyeongsan.cabinet.application.kakaonotify;

import com.gyeongsan.cabinet.domain.auth.port.out.LinkRedirectUriPort;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoGrant;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoInvalidGrantException;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotificationException;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotifyStatus;
import com.gyeongsan.cabinet.domain.kakaonotify.port.in.GrantKakaoNotifyConsentUseCase;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoConsentRepositoryPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoLoginLinkPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoNotificationPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.TokenCipherPort;
import com.gyeongsan.cabinet.global.exception.ErrorCode;
import com.gyeongsan.cabinet.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Log4j2
@RequiredArgsConstructor
public class KakaoNotifyConsentService implements GrantKakaoNotifyConsentUseCase {

    static final String REQUIRED_SCOPE = "talk_message";

    private final KakaoLoginLinkPort loginLinkPort;
    private final LinkRedirectUriPort redirectUriPort;
    private final KakaoNotificationPort kakao;
    private final KakaoConsentRepositoryPort consentRepository;
    private final TokenCipherPort cipher;
    private final Clock clock;

    @Override
    public KakaoNotifyStatus grantConsent(Long userId, String authorizationCode) {
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new ServiceException(ErrorCode.KAKAO_NOTIFY_CODE_INVALID);
        }

        String linkedProviderId =
                loginLinkPort
                        .findKakaoProviderId(userId)
                        .orElseThrow(
                                () -> new ServiceException(ErrorCode.KAKAO_NOTIFY_LINK_REQUIRED));

        KakaoGrant grant;
        try {
            grant =
                    kakao.exchangeAuthorizationCode(
                            authorizationCode.trim(), redirectUriPort.getLinkRedirectUri("kakao"));
        } catch (KakaoInvalidGrantException e) {
            throw new ServiceException(ErrorCode.KAKAO_NOTIFY_CODE_INVALID);
        } catch (KakaoNotificationException e) {
            log.warn("[KakaoNotify] 인가 코드 교환 실패 - userId: {}, 원인: {}", userId, e.getMessage());
            throw new ServiceException(ErrorCode.KAKAO_NOTIFY_UNAVAILABLE);
        }

        if (!linkedProviderId.equals(grant.providerId())) {
            log.warn("[KakaoNotify] 연동된 카카오 계정과 다른 계정으로 동의 시도 - userId: {}", userId);
            throw new ServiceException(ErrorCode.KAKAO_NOTIFY_ACCOUNT_MISMATCH);
        }
        if (!grant.scopes().contains(REQUIRED_SCOPE)) {
            throw new ServiceException(ErrorCode.KAKAO_NOTIFY_SCOPE_MISSING);
        }

        String encrypted =
                cipher.encrypt(grant.refreshToken(), SlackNoticeForwardService.context(userId));
        boolean activated =
                consentRepository.grant(
                        userId,
                        encrypted,
                        String.join(" ", new TreeSet<>(grant.scopes())),
                        LocalDateTime.now(clock));

        log.info("[KakaoNotify] 알림 동의 등록 - userId: {}, 알림 스위치 새로 켬: {}", userId, activated);
        return consentRepository.findStatus(userId);
    }
}
