package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class RedisConfigStartupTest {

    private static final Path MAIN_YML = Path.of("src/main/resources/application.yml");
    private static final Path PROD_YML = Path.of("src/main/resources/application-prod.yml");
    private static final String REMOTE = "cache.example.redis.test";
    private static final String SECRET = "super-secret-redis-pw-9f8e7d";

    private ApplicationContextRunner runner(boolean withProdYml) {
        return new ApplicationContextRunner()
                .withUserConfiguration(RedisConfig.class)
                .withInitializer(
                        context -> {
                            try {
                                if (withProdYml) {
                                    for (PropertySource<?> source :
                                            new YamlPropertySourceLoader()
                                                    .load(
                                                            "prod-application-yml",
                                                            new FileSystemResource(PROD_YML))) {
                                        context.getEnvironment()
                                                .getPropertySources()
                                                .addLast(source);
                                    }
                                }
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

    private static String rootMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("[사고 재현] 프로필이 prod 가 아니면 REDIS_PASSWORD 가 읽히지 않아 비밀번호가 비는데, 이제는 부팅이 실패한다")
    void nonProdProfileWithoutPasswordFailsFast() {
        runner(false)
                .withPropertyValues(
                        "REDIS_HOST=" + REMOTE, "REDIS_PORT=10000", "REDIS_PASSWORD=" + SECRET)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            String message = rootMessages(context.getStartupFailure());
                            assertThat(message)
                                    .contains("Redis 연결 설정이 안전하지 않아 부팅을 중단합니다")
                                    .contains("SPRING_DATA_REDIS_PASSWORD");
                            assertThat(message).doesNotContain(SECRET);
                        });
    }

    @Test
    @DisplayName("기본 설정만으로(환경변수 없이) 띄우면 부팅이 실패한다 — 인증 없는 연결로 조용히 시작하지 않는다")
    void defaultConfigurationDoesNotStart() {
        runner(false).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("prod 설정(REDIS_HOST/REDIS_PASSWORD, TLS)이 갖춰지면 정상 기동한다")
    void prodConfigurationStarts() {
        runner(true)
                .withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues("REDIS_HOST=" + REMOTE, "REDIS_PASSWORD=" + SECRET)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                        });
    }

    @Test
    @DisplayName("prod 가 아닌 프로필에서도 문서화된 방법(비밀번호·TLS 직접 주입)으로는 기동한다")
    void nonProdProfileWithExplicitSecureSettingsStarts() {
        runner(false)
                .withPropertyValues(
                        "REDIS_HOST=" + REMOTE,
                        "spring.data.redis.password=" + SECRET,
                        "spring.data.redis.ssl.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("로컬 개발: REDIS_ALLOW_INSECURE=true 를 명시하면 비밀번호 없이 기동한다")
    void explicitInsecureFlagStartsLocally() {
        runner(false)
                .withPropertyValues("REDIS_ALLOW_INSECURE=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("prod 프로필에서는 REDIS_ALLOW_INSECURE=true 를 줘도 부팅이 실패한다")
    void insecureFlagInProdFails() {
        runner(true)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "REDIS_HOST=" + REMOTE,
                        "REDIS_PASSWORD=" + SECRET,
                        "REDIS_ALLOW_INSECURE=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(rootMessages(context.getStartupFailure()))
                                    .contains("prod 프로필")
                                    .doesNotContain(SECRET);
                        });
    }

    @Test
    @DisplayName("운영 설정 파일의 기본값은 비인증 허용이 꺼져 있다")
    void insecureFlagDefaultsToOff() {
        runner(false)
                .withPropertyValues(
                        "spring.data.redis.password=" + SECRET,
                        "spring.data.redis.ssl.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "app.redis.allow-insecure",
                                                            Boolean.class))
                                    .isFalse();
                        });
    }
}
