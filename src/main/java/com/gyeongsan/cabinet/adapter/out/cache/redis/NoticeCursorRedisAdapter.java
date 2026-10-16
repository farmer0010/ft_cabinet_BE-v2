package com.gyeongsan.cabinet.adapter.out.cache.redis;

import com.gyeongsan.cabinet.domain.kakaonotify.port.out.NoticeCursorPort;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NoticeCursorRedisAdapter implements NoticeCursorPort {

    private static final String KEY_PREFIX = "slack:notice:cursor:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public Optional<String> getCursor(String channelId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + channelId));
    }

    @Override
    public void saveCursor(String channelId, String ts) {
        redisTemplate.opsForValue().set(KEY_PREFIX + channelId, ts);
    }
}
