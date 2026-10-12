package com.gyeongsan.cabinet.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Log4j2
public class RedisConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    /** 로컬/테스트에서 비인증·비암호화 연결을 쓰겠다는 명시적 선언. 기본 false, prod 프로필에서는 켤 수 없다. */
    @Value("${app.redis.allow-insecure:false}")
    private boolean allowInsecure;

    private final Environment environment;

    public RedisConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 비밀번호/TLS 가 없으면 여기서 부팅을 실패시킨다(프로필이 바뀌어 인증 없는 연결이 되는 것을 막는다).
        boolean insecure =
                RedisConnectionPolicy.validate(
                        host,
                        password,
                        sslEnabled,
                        allowInsecure,
                        environment.acceptsProfiles(Profiles.of("prod")));
        if (insecure) {
            log.warn(
                    "[Redis] app.redis.allow-insecure=true 로 비인증/비암호화 연결을 허용했습니다 - host: {}. 로컬 개발·테스트 전용입니다.",
                    host);
        }

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isEmpty()) {
            configuration.setPassword(password);
        }

        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder();
        if (sslEnabled) {
            builder.useSsl();
        }

        return new LettuceConnectionFactory(configuration, builder.build());
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());

        return template;
    }
}
