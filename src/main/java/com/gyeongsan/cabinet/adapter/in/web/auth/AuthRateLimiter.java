package com.gyeongsan.cabinet.adapter.in.web.auth;

import com.gyeongsan.cabinet.common.ratelimit.KeyedSlidingWindowLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    public boolean allowReissue(long userId) {
        return reissue.tryAcquire(userId);
    }

    public boolean allowLink(long userId) {
        return link.tryAcquire(userId);
    }
}
