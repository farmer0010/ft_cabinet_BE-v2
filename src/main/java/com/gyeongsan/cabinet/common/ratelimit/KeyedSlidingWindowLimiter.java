package com.gyeongsan.cabinet.common.ratelimit;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 키(사용자 ID 등)별 요청 횟수 제한(슬라이딩 윈도). Resilience4j 의 {@code @RateLimiter} 는 이름 하나가 <b>전역</b> 버킷이라 한
 * 요청자가 버킷을 소진하면 같은 이름을 쓰는 모든 사용자가 막힌다(감사 H-3). 이 한도기는 키마다 별도 창을 가져서 한 키의 폭주가 다른 키에 영향을 주지 않는다.
 *
 * <p>서버 메모리에만 두므로 서버가 여러 대면 한도가 서버별로 적용된다(서버 수만큼 느슨해지는 것은 수용). 키와 요청 시각만 들고 있고, 창이 지난 기록은 주기적으로 지워서
 * 메모리가 활성 키 수에 비례하게만 유지된다.
 *
 * <p><b>키는 호출한 쪽이 신뢰할 수 있는 값이어야 한다.</b> 요청 헤더(X-Forwarded-For 등)처럼 클라이언트가 바꿀 수 있는 값을 키로 쓰면 값을 바꿔 가며
 * 한도를 우회할 수 있으므로, 서명 검증이 끝난 토큰의 사용자 ID 같은 값을 쓴다.
 */
public final class KeyedSlidingWindowLimiter {

    private static final int SWEEP_EVERY_CALLS = 1024;

    private final int limit;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<Long, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();

    public KeyedSlidingWindowLimiter(int limit, long windowMillis) {
        this(limit, windowMillis, System::nanoTime);
    }

    KeyedSlidingWindowLimiter(int limit, long windowMillis, LongSupplier nanoClock) {
        if (limit <= 0 || windowMillis <= 0) {
            throw new IllegalArgumentException("한도와 시간 창은 양수여야 합니다.");
        }
        this.limit = limit;
        this.windowNanos = windowMillis * 1_000_000L;
        this.nanoClock = nanoClock;
    }

    /** 한도 안이면 이번 요청을 기록하고 true, 넘었으면 기록하지 않고 false. */
    public boolean tryAcquire(long key) {
        long now = nanoClock.getAsLong();
        boolean[] allowed = new boolean[1];
        hits.compute(
                key,
                (k, window) -> {
                    ArrayDeque<Long> deque = window == null ? new ArrayDeque<>() : window;
                    dropExpired(deque, now);
                    if (deque.size() >= limit) {
                        return deque;
                    }
                    deque.addLast(now);
                    allowed[0] = true;
                    return deque;
                });
        if (calls.incrementAndGet() % SWEEP_EVERY_CALLS == 0) {
            sweep(now);
        }
        return allowed[0];
    }

    int trackedKeys() {
        return hits.size();
    }

    private void sweep(long now) {
        for (Long key : hits.keySet()) {
            hits.computeIfPresent(
                    key,
                    (k, window) -> {
                        dropExpired(window, now);
                        return window.isEmpty() ? null : window;
                    });
        }
    }

    private void dropExpired(ArrayDeque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= windowNanos) {
            window.pollFirst();
        }
    }
}
