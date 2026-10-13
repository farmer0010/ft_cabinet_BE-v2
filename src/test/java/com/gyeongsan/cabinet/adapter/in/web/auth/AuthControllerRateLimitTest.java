package com.gyeongsan.cabinet.adapter.in.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.gyeongsan.cabinet.adapter.in.web.auth.dto.OAuthLinkRequest;
import com.gyeongsan.cabinet.adapter.out.persistence.user.UserRepository;
import com.gyeongsan.cabinet.auth.jwt.JwtTokenProvider;
import com.gyeongsan.cabinet.domain.auth.port.in.LinkAccountUseCase;
import com.gyeongsan.cabinet.domain.user.model.User;
import com.gyeongsan.cabinet.domain.user.model.UserRole;
import com.gyeongsan.cabinet.global.exception.ErrorCode;
import com.gyeongsan.cabinet.global.exception.GlobalExceptionHandler;
import com.gyeongsan.cabinet.global.exception.ServiceException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.springboot3.ratelimiter.autoconfigure.RateLimiterAutoConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 감사 H-3: 비인증 {@code /v4/auth/reissue} 요청이 전역 {@code userApi} 한도를 소진해 모든 사용자를 429 로 막던 문제. 한도 키를 서명
 * 검증된 refresh token 의 사용자 ID 로 분리했는지, X-Forwarded-For 위조로 우회되지 않는지 실제 Resilience4j AOP 와 실제 MVC
 * 디스패치로 확인한다.
 */
class AuthControllerRateLimitTest {

    private static final String SECRET =
            "test-secret-key-must-be-long-enough-for-hs256-algorithm-minimum-bits";

    /** 전역 {@code userApi} 버킷을 쓰는 다른 엔드포인트(상점, 수박, 관리자, 카카오 설정 등을 대신한다). */
    static class UserApiProbe {
        @RateLimiter(name = "userApi")
        public String call() {
            return "ok";
        }
    }

    static JwtTokenProvider jwt() {
        JwtTokenProvider provider = new JwtTokenProvider(mock(UserRepository.class));
        ReflectionTestUtils.setField(provider, "secretKey", SECRET);
        ReflectionTestUtils.setField(provider, "tokenValidTime", 1_800_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenValidTime", 1_209_600_000L);
        provider.init();
        return provider;
    }

    static User user(long id, String name) {
        User user = User.of(name, name + "@example.com", null, UserRole.USER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private JwtTokenProvider jwtTokenProvider;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> redisValues;
    private UserRepository userRepository;
    private AuthController controller;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        jwtTokenProvider = jwt();
        redis = mock(StringRedisTemplate.class);
        redisValues = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(redisValues);
        userRepository = mock(UserRepository.class);
        controller =
                new AuthController(
                        jwtTokenProvider,
                        redis,
                        userRepository,
                        mock(LinkAccountUseCase.class),
                        new AuthRateLimiter(3, 2));
    }

    private String loginAs(long userId, String name) {
        String refreshToken = jwtTokenProvider.createRefreshToken(userId);
        when(redisValues.get("RT:" + userId)).thenReturn(refreshToken);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, name)));
        return refreshToken;
    }

    @Configuration
    static class GlobalBucketConfig {
        @Bean
        AuthController authController() {
            return new AuthController(
                    jwt(),
                    mock(StringRedisTemplate.class),
                    mock(UserRepository.class),
                    mock(LinkAccountUseCase.class),
                    new AuthRateLimiter(20, 10));
        }

        @Bean
        UserApiProbe userApiProbe() {
            return new UserApiProbe();
        }
    }

    @Test
    @DisplayName(
            "[H-3 회귀] 비인증 reissue·link 폭주가 전역 userApi 버킷을 소진하지 않아, 다른 사용자의 userApi 호출은 계속 성공한다")
    void anonymousFloodDoesNotExhaustGlobalUserApiBucket() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                AopAutoConfiguration.class, RateLimiterAutoConfiguration.class))
                .withUserConfiguration(GlobalBucketConfig.class)
                .withPropertyValues(
                        "resilience4j.ratelimiter.instances.userApi.limitForPeriod=3",
                        "resilience4j.ratelimiter.instances.userApi.limitRefreshPeriod=1h",
                        "resilience4j.ratelimiter.instances.userApi.timeoutDuration=0s")
                .run(
                        context -> {
                            AuthController auth = context.getBean(AuthController.class);
                            UserApiProbe probe = context.getBean(UserApiProbe.class);

                            // 쿠키 없는 reissue, 서명이 틀린 reissue, 로그인 없는 link 를 대량으로 보낸다.
                            for (int i = 0; i < 300; i++) {
                                assertThatThrownBy(
                                                () ->
                                                        auth.reissue(
                                                                null,
                                                                new MockHttpServletResponse()))
                                        .isInstanceOf(IllegalArgumentException.class);
                                assertThatThrownBy(
                                                () ->
                                                        auth.reissue(
                                                                "not.a.jwt." + System.nanoTime(),
                                                                new MockHttpServletResponse()))
                                        .isInstanceOf(IllegalArgumentException.class);
                                assertThatThrownBy(
                                                () ->
                                                        auth.linkSocialAccount(
                                                                null,
                                                                "kakao",
                                                                new OAuthLinkRequest()))
                                        .isInstanceOf(ServiceException.class);
                            }

                            // 전역 버킷(3회)은 그대로여야 한다. 수정 전에는 첫 호출부터 RequestNotPermitted 였다.
                            assertThat(probe.call()).isEqualTo("ok");
                            assertThat(probe.call()).isEqualTo("ok");
                            assertThat(probe.call()).isEqualTo("ok");
                            // 대조: 한도 설정이 실제로 적용되고 있다는 확인.
                            assertThatThrownBy(probe::call).isInstanceOf(RequestNotPermitted.class);
                        });
    }

    @Test
    @DisplayName("쿠키가 없거나 서명이 틀린 요청은 Redis·DB 를 건드리지 않고 사용자별 한도도 소모하지 않는다")
    void unverifiedRequestsTouchNothing() {
        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> controller.reissue(null, new MockHttpServletResponse()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.reissue("garbage", new MockHttpServletResponse()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(redisValues);
        verifyNoInteractions(userRepository);

        // 한도가 전혀 소모되지 않았으므로 정상 사용자는 한도(3회)만큼 그대로 쓸 수 있다.
        String refreshToken = loginAs(7L, "intra07");
        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> controller.reissue(refreshToken, new MockHttpServletResponse()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("같은 사용자가 한도를 넘으면 429(TOO_MANY_REQUESTS)이고, 넘은 요청은 Redis 조회까지 가지 않는다")
    void sameUserOverLimitGets429() {
        String refreshToken = loginAs(7L, "intra07");

        for (int i = 0; i < 3; i++) {
            controller.reissue(refreshToken, new MockHttpServletResponse());
        }
        assertThatThrownBy(() -> controller.reissue(refreshToken, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        verify(redisValues, org.mockito.Mockito.times(3)).get("RT:7");
    }

    @Test
    @DisplayName("한 사용자가 한도를 넘어도 다른 사용자의 재발급은 영향을 받지 않는다")
    void otherUsersAreUnaffected() {
        String tokenA = loginAs(7L, "intra07");
        String tokenB = loginAs(8L, "intra08");

        for (int i = 0; i < 50; i++) {
            try {
                controller.reissue(tokenA, new MockHttpServletResponse());
            } catch (ServiceException ignored) {
                // 한도 초과
            }
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.reissue(tokenB, response);
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("access_token="));
    }

    @Test
    @DisplayName("정상 재발급은 기존과 같이 access_token 쿠키를 내려 준다 (동작 유지)")
    void normalReissueStillWorks() {
        String refreshToken = loginAs(7L, "intra07");

        MockHttpServletResponse response = new MockHttpServletResponse();
        var body = controller.reissue(refreshToken, response);

        assertThat(body.isSuccess()).isTrue();
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("access_token="));
    }

    @Test
    @DisplayName("Redis 의 토큰과 다르면 기존대로 거부된다 (한도 변경이 토큰 검증을 바꾸지 않는다)")
    void tokenMismatchStillRejected() {
        String refreshToken = jwtTokenProvider.createRefreshToken(7L);
        when(redisValues.get("RT:7")).thenReturn("some-other-token");

        assertThatThrownBy(() -> controller.reissue(refreshToken, new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일치하지 않습니다");
        verify(userRepository, never()).findById(7L);
    }

    @Test
    @DisplayName("link: 로그인하지 않은 요청은 NPE(500)가 아니라 401 이고, 로그인한 사용자는 사용자별 한도가 적용된다")
    void linkRequiresAuthenticationAndIsLimitedPerUser() {
        assertThatThrownBy(
                        () -> controller.linkSocialAccount(null, "kakao", new OAuthLinkRequest()))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
        assertThat(ErrorCode.AUTHENTICATION_REQUIRED.getStatus().value()).isEqualTo(401);

        var principalA =
                new com.gyeongsan.cabinet.auth.domain.UserPrincipal(
                        user(7L, "a"), java.util.Map.of());
        var principalB =
                new com.gyeongsan.cabinet.auth.domain.UserPrincipal(
                        user(8L, "b"), java.util.Map.of());
        OAuthLinkRequest request = new OAuthLinkRequest();
        ReflectionTestUtils.setField(request, "authorizationCode", "code");

        controller.linkSocialAccount(principalA, "kakao", request);
        controller.linkSocialAccount(principalA, "kakao", request);
        assertThatThrownBy(() -> controller.linkSocialAccount(principalA, "kakao", request))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        // 다른 사용자는 영향이 없다.
        controller.linkSocialAccount(principalB, "kakao", request);
    }

    @Test
    @DisplayName("[스푸핑] X-Forwarded-For 값을 바꿔 가며 보내도 사용자별 한도를 우회할 수 없고, 같은 헤더로 다른 사용자를 막을 수도 없다")
    void forgedForwardedForHeaderCannotBypassOrHurtOthers() throws Exception {
        String tokenA = loginAs(7L, "intra07");
        String tokenB = loginAs(8L, "intra08");
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();

        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            int status =
                    mvc.perform(
                                    post("/v4/auth/reissue")
                                            .cookie(new Cookie("refresh_token", tokenA))
                                            // 요청마다 다른 위조 IP
                                            .header(
                                                    "X-Forwarded-For",
                                                    "198.51.100." + i + ", 203.0.113." + i)
                                            .header("Forwarded", "for=192.0.2." + i))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            if (status == 200) ok++;
            else if (status == 429) limited++;
        }
        assertThat(ok).as("위조 헤더를 바꿔도 허용 횟수는 한도(3)를 넘지 않는다").isEqualTo(3);
        assertThat(limited).isEqualTo(9);

        // A 가 쓰던 위조 IP 를 B 가 그대로 써도 B 는 막히지 않는다(IP 로 키를 잡으면 생기는 문제).
        int statusB =
                mvc.perform(
                                post("/v4/auth/reissue")
                                        .cookie(new Cookie("refresh_token", tokenB))
                                        .header("X-Forwarded-For", "198.51.100.1"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(statusB).isEqualTo(200);

        // A 의 Redis 조회는 허용된 3번뿐이다(한도를 넘은 9번은 Redis 까지 가지 않았다).
        verify(redisValues, org.mockito.Mockito.times(3)).get("RT:7");
    }
}
