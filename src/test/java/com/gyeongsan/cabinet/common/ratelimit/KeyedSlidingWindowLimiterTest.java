package com.gyeongsan.cabinet.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeyedSlidingWindowLimiterTest {

    private final AtomicLong nowNanos = new AtomicLong();

    private KeyedSlidingWindowLimiter limiter(int limit, long windowMillis) {
        return new KeyedSlidingWindowLimiter(limit, windowMillis, nowNanos::get);
    }

    @Test
    @DisplayName("한도까지는 허용하고 넘으면 거부하며, 거부된 요청은 창에 기록되지 않는다")
    void allowsUpToLimitThenRejects() {
        KeyedSlidingWindowLimiter limiter = limiter(3, 1_000);

        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();
        assertThat(limiter.tryAcquire(1)).isFalse();
    }

    @Test
    @DisplayName("한 키가 한도를 넘어도 다른 키는 영향을 받지 않는다 (전역 버킷과의 차이)")
    void keysAreIndependent() {
        KeyedSlidingWindowLimiter limiter = limiter(2, 1_000);

        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire(1);
        }

        assertThat(limiter.tryAcquire(1)).isFalse();
        assertThat(limiter.tryAcquire(2)).isTrue();
        assertThat(limiter.tryAcquire(3)).isTrue();
    }

    @Test
    @DisplayName("창이 지나면 다시 허용된다")
    void windowSlides() {
        KeyedSlidingWindowLimiter limiter = limiter(2, 1_000);

        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();

        nowNanos.addAndGet(999_000_000L);
        assertThat(limiter.tryAcquire(1)).as("창이 끝나기 직전").isFalse();

        nowNanos.addAndGet(2_000_000L);
        assertThat(limiter.tryAcquire(1)).as("창이 지남").isTrue();
    }

    @Test
    @DisplayName("지난 기록은 정리되어 메모리가 활성 키 수에 비례하게만 유지된다")
    void expiredKeysAreSwept() {
        KeyedSlidingWindowLimiter limiter = limiter(5, 1_000);
        for (long key = 0; key < 3_000; key++) {
            limiter.tryAcquire(key);
        }
        assertThat(limiter.trackedKeys()).isGreaterThan(2_000);

        nowNanos.addAndGet(5_000_000_000L);
        // 정리는 일정 횟수마다 돌므로 충분히 호출한다.
        for (int i = 0; i < 1_100; i++) {
            limiter.tryAcquire(1_000_000L);
        }

        assertThat(limiter.trackedKeys()).isLessThan(10);
    }

    @Test
    @DisplayName("한도와 창은 양수여야 한다")
    void rejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new KeyedSlidingWindowLimiter(0, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyedSlidingWindowLimiter(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
