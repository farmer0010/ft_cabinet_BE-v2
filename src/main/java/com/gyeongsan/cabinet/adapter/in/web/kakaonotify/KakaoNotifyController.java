package com.gyeongsan.cabinet.adapter.in.web.kakaonotify;

import com.gyeongsan.cabinet.adapter.in.web.kakaonotify.dto.KakaoNotifyAlarmRequest;
import com.gyeongsan.cabinet.adapter.in.web.kakaonotify.dto.KakaoNotifyConsentRequest;
import com.gyeongsan.cabinet.adapter.in.web.kakaonotify.dto.KakaoNotifyStatusResponse;
import com.gyeongsan.cabinet.auth.domain.UserPrincipal;
import com.gyeongsan.cabinet.common.ApiResponse;
import com.gyeongsan.cabinet.domain.kakaonotify.port.in.GrantKakaoNotifyConsentUseCase;
import com.gyeongsan.cabinet.domain.kakaonotify.port.in.KakaoNotifySettingsUseCase;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v4/users/me/kakao-notify")
@ConditionalOnProperty(name = "app.kakao-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@RateLimiter(name = "userApi")
public class KakaoNotifyController {

    private final GrantKakaoNotifyConsentUseCase grantConsent;
    private final KakaoNotifySettingsUseCase settings;

    @GetMapping
    public ResponseEntity<ApiResponse<KakaoNotifyStatusResponse>> status(
            @AuthenticationPrincipal UserPrincipal principal) {
        return noStore(KakaoNotifyStatusResponse.from(settings.getStatus(principal.getUserId())));
    }

    @PostMapping("/consent")
    public ResponseEntity<ApiResponse<KakaoNotifyStatusResponse>> consent(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody KakaoNotifyConsentRequest request) {
        return noStore(
                KakaoNotifyStatusResponse.from(
                        grantConsent.grantConsent(
                                principal.getUserId(), request.getAuthorizationCode())));
    }

    @PutMapping("/alarm")
    public ResponseEntity<ApiResponse<KakaoNotifyStatusResponse>> alarm(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody KakaoNotifyAlarmRequest request) {
        if (request.getEnabled() == null) {
            throw new IllegalArgumentException("enabled 값이 필요합니다.");
        }
        return noStore(
                KakaoNotifyStatusResponse.from(
                        settings.setAlarm(principal.getUserId(), request.getEnabled())));
    }

    private static ResponseEntity<ApiResponse<KakaoNotifyStatusResponse>> noStore(
            KakaoNotifyStatusResponse body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(body));
    }
}
