package com.gyeongsan.cabinet.adapter.in.web.auth;

import com.gyeongsan.cabinet.common.ratelimit.KeyedSlidingWindowLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 인증 관련 엔드포인트의 사용자별 한도. 전역 {@code userApi} 버킷을 대신한다.
 *
 * <p>이전에는 {@code AuthController} 전체가 전역 {@code userApi}(초당 50회, 대기 0초)를 썼는데, {@code /v4/auth/**} 는
 * 비로그인으로 열려 있어서 비인증 요청이 같은 버킷을 소진하면 상점·수박·관리자·카카오 설정 API 를 쓰는 모든 사용자가 429 를 받았고 토큰 재발급도 막혔다(감사
 * H-3).
 *
 * <p>키는 <b>서명 검증이 끝난 refresh token 의 사용자 ID</b> 다. IP 는 쓰지 않는다: 이 서비스는 Azure 프런트엔드와 nginx 뒤에 있고
 * nginx 가 클라이언트가 보낸 {@code X-Forwarded-For} 를 그대로 이어 붙이므로(`$proxy_add_x_forwarded_for`) 헤더 값을 바꿔 가며
 * 한도를 우회할 수 있고, 반대로 {@code getRemoteAddr()} 는 모두 프록시 주소라 한 버킷으로 합쳐진다. 서명 검증에 실패하는 요청은 어떤 한도도 소모하지
 * 않고 Redis·DB 도 건드리지 않는다.
 */
@Component
public class AuthRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final KeyedSlidingWindowLimiter reissue;
    private final KeyedSlidingWindowLimiter link;

    public AuthRateLimiter(
            @Value("${app.rate-limit.auth.reissue-per-minute:20}") int reissuePerMinute,
            @Value("${app.rate-limit.auth.link-per-minute:10}") int linkPerMinute) {
        this.reissue = new KeyedSlidingWindowLimiter(reissuePerMinute, WINDOW_MILLIS);
        this.link = new KeyedSlidingWindowLimiter(linkPerMinute, WINDOW_MILLIS);
    }

    /** 토큰 재발급. {@code userId} 는 서명 검증을 통과한 refresh token 의 subject 여야 한다. */
    public boolean allowReissue(long userId) {
        return reissue.tryAcquire(userId);
    }

    /** 소셜 계정 연동. {@code userId} 는 인증된 사용자의 ID 여야 한다. */
    public boolean allowLink(long userId) {
        return link.tryAcquire(userId);
    }
}
