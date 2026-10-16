package com.gyeongsan.cabinet.application.chatbot;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class PerUserRateLimiter {

    private static final int SWEEP_EVERY_CALLS = 1024;

    private final int limit;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<Long, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();

    public PerUserRateLimiter(int limit, long windowMillis) {
        this(limit, windowMillis, System::nanoTime);
    }

    PerUserRateLimiter(int limit, long windowMillis, LongSupplier nanoClock) {
        if (limit <= 0 || windowMillis <= 0) {
            throw new IllegalArgumentException("사용자별 한도와 시간 창은 양수여야 합니다.");
        }
        this.limit = limit;
        this.windowNanos = windowMillis * 1_000_000L;
        this.nanoClock = nanoClock;
    }

    public boolean tryAcquire(long userId) {
        long now = nanoClock.getAsLong();
        boolean[] allowed = new boolean[1];
        hits.compute(
                userId,
                (id, window) -> {
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

    int trackedUsers() {
        return hits.size();
    }

    private void sweep(long now) {
        for (Long userId : hits.keySet()) {
            hits.computeIfPresent(
                    userId,
                    (id, window) -> {
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
