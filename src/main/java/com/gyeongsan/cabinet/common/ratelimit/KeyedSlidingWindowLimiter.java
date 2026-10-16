package com.gyeongsan.cabinet.common.ratelimit;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

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
