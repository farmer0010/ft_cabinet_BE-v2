package com.gyeongsan.cabinet.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Redis 연결 설정의 안전 규칙. 부팅 시점에 한 번 검사해서, 안전하지 않은 설정이면 앱을 띄우지 않는다(fail-fast).
 *
 * <p>비밀번호와 TLS 설정이 {@code application-prod.yml} 에만 있던 시절에는 프로필 이름을 바꾸는 것만으로 비밀번호가 빈 값이 되어 <b>경고 없이
 * 인증 없는 연결</b>을 시도했고, 앱은 정상 기동한 채 로그인(RT 저장)·분산락·ShedLock 이 깨졌다(감사 H-6, 실제로 겪은 사고). 규칙:
 *
 * <ul>
 *   <li>비밀번호가 비어 있으면 거부한다.
 *   <li>TLS 가 꺼져 있고 호스트가 루프백(로컬)이 아니면 거부한다. 비밀번호가 평문으로 네트워크를 지나가기 때문이다.
 *   <li>로컬/테스트에서 비인증 연결이 필요하면 {@code app.redis.allow-insecure=true} 를 <b>명시</b>해야만 허용한다. 루프백이라고 해서
 *       자동으로 허용하지 않는다. 단 {@code prod} 프로필에서는 이 예외도 허용하지 않는다.
 * </ul>
 *
 * 비밀번호 값은 어떤 메시지에도 담지 않는다.
 */
final class RedisConnectionPolicy {

    private static final Pattern LOOPBACK_IPV4 =
            Pattern.compile("127\\.(?:\\d{1,3})\\.(?:\\d{1,3})\\.(?:\\d{1,3})");

    private RedisConnectionPolicy() {}

    /**
     * @return 예외 허용({@code allowInsecure})으로 비인증·비암호화 연결이 실제로 쓰이게 되면 true (호출한 쪽이 경고를 남긴다)
     * @throws IllegalStateException 규칙을 어기면
     */
    static boolean validate(
            String host,
            String password,
            boolean sslEnabled,
            boolean allowInsecure,
            boolean prodProfileActive) {
        boolean passwordMissing = password == null || password.isBlank();
        boolean tlsMissingOnRemote = !sslEnabled && !isLoopback(host);

        if (allowInsecure) {
            if (prodProfileActive) {
                throw new IllegalStateException(
                        "prod 프로필에서는 app.redis.allow-insecure(REDIS_ALLOW_INSECURE)를 켤 수 없습니다. "
                                + "Redis 비밀번호와 TLS 를 설정하세요.");
            }
            return passwordMissing || !sslEnabled;
        }

        List<String> problems = new ArrayList<>();
        if (passwordMissing) {
            problems.add("비밀번호가 없습니다 (spring.data.redis.password)");
        }
        if (tlsMissingOnRemote) {
            problems.add("원격 호스트 '" + host + "' 에 TLS 가 꺼져 있습니다 (spring.data.redis.ssl.enabled)");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Redis 연결 설정이 안전하지 않아 부팅을 중단합니다: "
                            + String.join(", ", problems)
                            + ". prod 프로필은 REDIS_HOST/REDIS_PORT/REDIS_PASSWORD 를 읽고 TLS 를 켠다. "
                            + "다른 프로필 이름으로 실행 중이라면 SPRING_DATA_REDIS_PASSWORD 와 "
                            + "SPRING_DATA_REDIS_SSL_ENABLED=true 를 직접 주입하세요. "
                            + "로컬 개발에서 의도적으로 비인증 연결을 쓰려면 REDIS_ALLOW_INSECURE=true 를 명시하세요.");
        }
        return false;
    }

    static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        // 127.evil.example 같은 DNS 이름을 루프백으로 오인하지 않도록 IPv4 리터럴 형태만 인정한다.
        return h.equals("localhost")
                || h.equals("::1")
                || h.equals("0:0:0:0:0:0:0:1")
                || LOOPBACK_IPV4.matcher(h).matches();
    }
}
