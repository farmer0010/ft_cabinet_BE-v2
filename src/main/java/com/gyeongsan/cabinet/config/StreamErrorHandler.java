package com.gyeongsan.cabinet.config;

import java.time.Duration;
import lombok.extern.log4j.Log4j2;
import org.springframework.util.ErrorHandler;

/**
 * Redis Streams 구독 루프의 오류 처리기.
 *
 * <p>spring-data-redis 의 구독은 기본값이 '첫 오류에 구독 취소'라, 연결 끊김·스트림 키 삭제(NOGROUP) 같은 일시 오류 한 번에 소비가 영구히
 * 멈췄다. 구독은 취소하지 않고 이 처리기가 오류를 기록한 뒤 잠깐 쉬어, Redis 가 계속 죽어 있어도 오류 로그가 폭주하거나 CPU 를 태우지 않게 한다. 그룹이
 * 사라졌다면(NOGROUP) 그룹을 되살리는 콜백을 부른다.
 *
 * <p>로그에는 예외 종류와 Redis 오류 메시지만 남긴다(메시지 본문·사용자 정보는 이 계층에 오지 않는다).
 */
@Log4j2
public class StreamErrorHandler implements ErrorHandler {

    /** 쉬는 방법. 테스트에서 실제로 기다리지 않도록 바꿀 수 있다. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final String streamKey;
    private final Runnable recreateGroup;
    private final Duration backoff;
    private final Sleeper sleeper;

    public StreamErrorHandler(String streamKey, Runnable recreateGroup, Duration backoff) {
        this(streamKey, recreateGroup, backoff, d -> Thread.sleep(d.toMillis()));
    }

    StreamErrorHandler(
            String streamKey, Runnable recreateGroup, Duration backoff, Sleeper sleeper) {
        this.streamKey = streamKey;
        this.recreateGroup = recreateGroup;
        this.backoff = backoff;
        this.sleeper = sleeper;
    }

    @Override
    public void handleError(Throwable t) {
        log.warn(
                "[RedisStream] {} 구독 중 오류가 났지만 구독은 유지합니다: {} {}",
                streamKey,
                t.getClass().getSimpleName(),
                rootMessage(t));
        if (isMissingGroup(t)) {
            try {
                recreateGroup.run();
                log.info("[RedisStream] {} 소비자 그룹을 다시 만들었습니다 (남아 있는 메시지부터 읽습니다)", streamKey);
            } catch (RuntimeException e) {
                log.warn(
                        "[RedisStream] {} 소비자 그룹 재생성 실패: {}",
                        streamKey,
                        e.getClass().getSimpleName());
            }
        }
        try {
            sleeper.sleep(backoff);
        } catch (InterruptedException e) {
            // 종료 신호: 플래그를 되살려 구독 루프가 스스로 끝나게 한다.
            Thread.currentThread().interrupt();
        }
    }

    static boolean isMissingGroup(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            String message = c.getMessage();
            if (message != null && message.contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? "" : root.getMessage();
    }
}
