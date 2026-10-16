package com.gyeongsan.cabinet.config;

import java.time.Duration;
import lombok.extern.log4j.Log4j2;
import org.springframework.util.ErrorHandler;

@Log4j2
public class StreamErrorHandler implements ErrorHandler {

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
