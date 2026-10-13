package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisConnectionPolicyTest {

    private static final String REMOTE = "cache.example.redis.test";
    private static final String SECRET = "super-secret-redis-pw-9f8e7d";

    @Test
    @DisplayName("원격 호스트 + 비밀번호 + TLS 면 통과하고, 비인증 예외를 쓰지 않았으니 false")
    void secureRemoteConfigPasses() {
        assertThat(RedisConnectionPolicy.validate(REMOTE, SECRET, true, false, true)).isFalse();
    }

    @Test
    @DisplayName("비밀번호가 비어 있거나 공백뿐이면 거부한다 (프로필이 바뀌어 prod 설정이 빠진 상황)")
    void blankPasswordIsRejected() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(
                            () -> RedisConnectionPolicy.validate(REMOTE, blank, true, false, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("비밀번호가 없습니다")
                    .hasMessageContaining("SPRING_DATA_REDIS_PASSWORD");
        }
    }

    @Test
    @DisplayName("원격 호스트인데 TLS 가 꺼져 있으면 거부한다")
    void remoteWithoutTlsIsRejected() {
        assertThatThrownBy(
                        () -> RedisConnectionPolicy.validate(REMOTE, SECRET, false, false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS 가 꺼져 있습니다")
                .hasMessageContaining(REMOTE);
    }

    @Test
    @DisplayName("둘 다 없으면 두 문제를 모두 알려 준다")
    void bothProblemsAreReported() {
        assertThatThrownBy(() -> RedisConnectionPolicy.validate(REMOTE, "", false, false, false))
                .hasMessageContaining("비밀번호가 없습니다")
                .hasMessageContaining("TLS 가 꺼져 있습니다");
    }

    @Test
    @DisplayName("루프백 호스트에서는 TLS 가 꺼져 있어도 비밀번호만 있으면 통과한다")
    void loopbackAllowsPlaintextWithPassword() {
        for (String host :
                new String[] {"localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "::1", "[::1]"}) {
            assertThatCode(() -> RedisConnectionPolicy.validate(host, SECRET, false, false, false))
                    .as(host)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("루프백이라도 비밀번호가 없으면 명시적 플래그 없이는 거부한다 (자동 허용 없음)")
    void loopbackWithoutPasswordNeedsExplicitFlag() {
        assertThatThrownBy(
                        () -> RedisConnectionPolicy.validate("127.0.0.1", "", false, false, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("루프백처럼 보이지만 아닌 호스트 이름(DNS 이름 등)은 루프백으로 취급하지 않는다")
    void lookalikeHostsAreNotLoopback() {
        for (String host :
                new String[] {
                    "localhost.evil.example",
                    "127.evil.example",
                    "127.0.0.1.evil.example",
                    "128.0.0.1",
                    "10.0.0.1",
                    "1270.0.0.1",
                    ""
                }) {
            assertThat(RedisConnectionPolicy.isLoopback(host)).as(host).isFalse();
        }
        assertThat(RedisConnectionPolicy.isLoopback(null)).isFalse();
        // 루프백 대역 전체는 인정한다.
        assertThat(RedisConnectionPolicy.isLoopback("127.0.0.1")).isTrue();
        assertThat(RedisConnectionPolicy.isLoopback("127.255.255.254")).isTrue();
    }

    @Test
    @DisplayName("루프백처럼 보이는 DNS 이름 + TLS 없음은 원격으로 보고 거부한다")
    void lookalikeDnsNameWithoutTlsIsRejected() {
        assertThatThrownBy(
                        () ->
                                RedisConnectionPolicy.validate(
                                        "127.evil.example", SECRET, false, false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS 가 꺼져 있습니다");
    }

    @Test
    @DisplayName("allow-insecure=true 를 명시하면 비밀번호·TLS 없이도 허용하고, 비인증으로 쓰이니 true 를 돌려준다")
    void explicitFlagAllowsInsecure() {
        assertThat(RedisConnectionPolicy.validate(REMOTE, "", false, true, false)).isTrue();
        assertThat(RedisConnectionPolicy.validate("localhost", "", false, true, false)).isTrue();
    }

    @Test
    @DisplayName("allow-insecure=true 여도 실제로는 안전한 설정이면 경고 대상(true)이 아니다")
    void flagWithSecureConfigIsNotInsecure() {
        assertThat(RedisConnectionPolicy.validate(REMOTE, SECRET, true, true, false)).isFalse();
    }

    @Test
    @DisplayName("prod 프로필에서는 allow-insecure=true 도 허용하지 않는다")
    void insecureFlagIsRejectedInProd() {
        assertThatThrownBy(() -> RedisConnectionPolicy.validate(REMOTE, "", false, true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod 프로필");
    }

    @Test
    @DisplayName("어떤 실패 메시지에도 비밀번호 값은 나오지 않는다")
    void passwordValueNeverAppearsInMessages() {
        assertThatThrownBy(
                        () -> RedisConnectionPolicy.validate(REMOTE, SECRET, false, false, false))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET));
        assertThatThrownBy(() -> RedisConnectionPolicy.validate(REMOTE, SECRET, true, true, true))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET));
    }
}
