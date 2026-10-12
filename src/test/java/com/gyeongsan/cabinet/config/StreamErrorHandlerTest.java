package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;

class StreamErrorHandlerTest {

    private final AtomicInteger recreated = new AtomicInteger();
    private final List<Duration> slept = new ArrayList<>();

    private StreamErrorHandler handler(Runnable recreate) {
        return new StreamErrorHandler("s", recreate, Duration.ofSeconds(2), d -> slept.add(d));
    }

    @Test
    @DisplayName("NOGROUP 오류(원인 사슬 안쪽 포함)면 그룹을 다시 만들고, 예외를 던지지 않고 잠깐 쉰다")
    void missingGroupRecreatesGroupAndBacksOff() {
        Throwable nogroup =
                new RedisSystemException(
                        "Error in execution",
                        new RuntimeException("NOGROUP No such key 'x' or consumer group 'g'"));

        assertThatCode(() -> handler(recreated::incrementAndGet).handleError(nogroup))
                .doesNotThrowAnyException();

        assertThat(recreated).hasValue(1);
        assertThat(slept).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("그 밖의 오류는 그룹을 건드리지 않고 쉬기만 한다 (구독은 호출자가 유지한다)")
    void otherErrorsOnlyBackOff() {
        handler(recreated::incrementAndGet).handleError(new RuntimeException("connection reset"));

        assertThat(recreated).hasValue(0);
        assertThat(slept).hasSize(1);
    }

    @Test
    @DisplayName("그룹 재생성이 실패해도 처리기는 예외를 던지지 않는다")
    void recreateFailureDoesNotPropagate() {
        assertThatCode(
                        () ->
                                handler(
                                                () -> {
                                                    throw new IllegalStateException("redis down");
                                                })
                                        .handleError(new RuntimeException("NOGROUP")))
                .doesNotThrowAnyException();
        assertThat(slept).hasSize(1);
    }

    @Test
    @DisplayName("쉬는 도중 인터럽트되면 인터럽트 플래그를 되살린다 (구독 루프가 스스로 끝나도록)")
    void interruptIsRestored() {
        StreamErrorHandler h =
                new StreamErrorHandler(
                        "s",
                        () -> {},
                        Duration.ofSeconds(2),
                        d -> {
                            throw new InterruptedException();
                        });

        h.handleError(new RuntimeException("x"));

        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    @DisplayName("원인 사슬이 자기 자신을 가리켜도 무한 루프에 빠지지 않는다")
    void selfReferencingCauseTerminates() {
        Throwable t =
                new RuntimeException("x") {
                    @Override
                    public synchronized Throwable getCause() {
                        return this;
                    }
                };

        assertThat(StreamErrorHandler.isMissingGroup(t)).isFalse();
    }
}
