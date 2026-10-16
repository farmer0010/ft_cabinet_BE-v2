package com.gyeongsan.cabinet.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class RedisConnectionPolicy {

    private static final Pattern LOOPBACK_IPV4 =
            Pattern.compile("127\\.(?:\\d{1,3})\\.(?:\\d{1,3})\\.(?:\\d{1,3})");

    private RedisConnectionPolicy() {}

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
        return h.equals("localhost")
                || h.equals("::1")
                || h.equals("0:0:0:0:0:0:0:1")
                || LOOPBACK_IPV4.matcher(h).matches();
    }
}
