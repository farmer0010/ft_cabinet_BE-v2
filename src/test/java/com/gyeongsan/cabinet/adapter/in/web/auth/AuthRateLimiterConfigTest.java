package com.gyeongsan.cabinet.adapter.in.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.filter.ForwardedHeaderFilter;

/** 운영 설정 파일의 기본값과 환경변수 덮어쓰기, 그리고 X-Forwarded-For 가 서블릿 요청 주소에 반영되지 않는다는 사실을 확인한다. */
class AuthRateLimiterConfigTest {

    private static final Path MAIN_YML = Path.of("src/main/resources/application.yml");

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(AuthRateLimiter.class)
                .withInitializer(
                        context -> {
                            try {
                                for (PropertySource<?> source :
                                        new YamlPropertySourceLoader()
                                                .load(
                                                        "main-application-yml",
                                                        new FileSystemResource(MAIN_YML))) {
                                    context.getEnvironment().getPropertySources().addLast(source);
                                }
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        });
    }

    @Test
    @DisplayName("기본 한도는 재발급 분당 20회, 연동 분당 10회이다")
    void defaults() {
        runner().run(
                        context -> {
                            AuthRateLimiter limiter = context.getBean(AuthRateLimiter.class);
                            int reissue = 0;
                            while (limiter.allowReissue(1L) && reissue < 100) reissue++;
                            int link = 0;
                            while (limiter.allowLink(1L) && link < 100) link++;
                            assertThat(reissue).isEqualTo(20);
                            assertThat(link).isEqualTo(10);
                        });
    }

    @Test
    @DisplayName("환경변수(AUTH_REISSUE_LIMIT_PER_MINUTE, AUTH_LINK_LIMIT_PER_MINUTE)로 한도를 바꿀 수 있다")
    void overridable() {
        runner().withPropertyValues(
                        "AUTH_REISSUE_LIMIT_PER_MINUTE=5", "AUTH_LINK_LIMIT_PER_MINUTE=2")
                .run(
                        context -> {
                            AuthRateLimiter limiter = context.getBean(AuthRateLimiter.class);
                            int reissue = 0;
                            while (limiter.allowReissue(1L) && reissue < 100) reissue++;
                            int link = 0;
                            while (limiter.allowLink(1L) && link < 100) link++;
                            assertThat(reissue).isEqualTo(5);
                            assertThat(link).isEqualTo(2);
                        });
    }

    @Test
    @DisplayName("0 이하로 설정하면 부팅이 실패한다 (한도가 없는 상태로 조용히 뜨지 않는다)")
    void nonPositiveLimitFailsStartup() {
        runner().withPropertyValues("AUTH_REISSUE_LIMIT_PER_MINUTE=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName(
            "[스푸핑 분석] forward-headers-strategy=framework 에서는 클라이언트가 보낸 X-Forwarded-For 의 첫 값이 getRemoteAddr() 가 된다")
    void forwardedHeaderFilterLetsClientSpoofRemoteAddr() throws Exception {
        // 실제 경로: 클라이언트 → Azure 프런트엔드 → nginx($proxy_add_x_forwarded_for 는 클라이언트가 보낸 값 뒤에 덧붙인다) → 앱.
        // 그래서 앱이 받는 헤더는 "<클라이언트가 쓴 값>, <진짜 주소>" 형태이고, 첫 값은 공격자가 정한다.
        MockHttpServletRequest request =
                new MockHttpServletRequest(HttpMethod.POST.name(), "/v4/auth/reissue");
        request.setRemoteAddr("10.1.2.3"); // 직접 연결한 프록시(nginx)의 주소
        request.addHeader("X-Forwarded-For", "203.0.113.77, 198.51.100.9");
        request.addHeader("X-Forwarded-Proto", "https");

        final String[] seenRemoteAddr = new String[1];
        new ForwardedHeaderFilter()
                .doFilter(
                        request,
                        new org.springframework.mock.web.MockHttpServletResponse(),
                        (req, res) -> seenRemoteAddr[0] = req.getRemoteAddr());

        assertThat(seenRemoteAddr[0])
                .as(
                        "앱이 보는 접속 주소가 클라이언트가 정한 첫 X-Forwarded-For 값이다 → getRemoteAddr() 로 IP 한도를 걸면 "
                                + "헤더만 바꿔 우회할 수 있다. 그래서 한도 키로 IP 를 쓰지 않고 서명 검증된 토큰의 사용자 ID 를 쓴다")
                .isEqualTo("203.0.113.77")
                .isNotEqualTo("10.1.2.3");
    }
}
