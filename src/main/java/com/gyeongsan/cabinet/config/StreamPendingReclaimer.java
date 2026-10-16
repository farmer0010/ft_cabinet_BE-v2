package com.gyeongsan.cabinet.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;

@Log4j2
public class StreamPendingReclaimer implements InitializingBean, DisposableBean {

    static final String DEAD_LETTER_SUFFIX = ":dead";
    private static final long DEAD_LETTER_MAX_LENGTH = 1_000;
    private static final long PAGE_SIZE = 100;

    private final StringRedisTemplate template;
    private final String group;
    private final String consumerName;
    private final Map<String, StreamListener<String, MapRecord<String, String, String>>> listeners;
    private final Duration minIdle;
    private final long maxDeliveries;
    private final Duration interval;
    private final Duration initialDelay;
    private ScheduledExecutorService scheduler;

    public StreamPendingReclaimer(
            StringRedisTemplate template,
            String group,
            String consumerName,
            Map<String, StreamListener<String, MapRecord<String, String, String>>> listeners,
            Duration minIdle,
            long maxDeliveries,
            Duration interval,
            Duration initialDelay) {
        this.template = template;
        this.group = group;
        this.consumerName = consumerName;
        this.listeners = new LinkedHashMap<>(listeners);
        this.minIdle = minIdle;
        this.maxDeliveries = maxDeliveries;
        this.interval = interval;
        this.initialDelay = initialDelay;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "stream-pending-reclaimer");
                            t.setDaemon(true);
                            return t;
                        });
        scheduler.scheduleWithFixedDelay(
                this::reclaimAllSafely,
                initialDelay.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void reclaimAllSafely() {
        try {
            reclaimOnce();
        } catch (RuntimeException e) {
            log.warn("[RedisStream] 방치된 메시지 회수 중 오류: {}", e.getClass().getSimpleName());
        }
    }

    public int reclaimOnce() {
        int handled = 0;
        for (Map.Entry<String, StreamListener<String, MapRecord<String, String, String>>> e :
                listeners.entrySet()) {
            try {
                handled += reclaim(e.getKey(), e.getValue());
            } catch (RuntimeException ex) {
                log.warn(
                        "[RedisStream] {} 방치된 메시지 회수 실패: {}",
                        e.getKey(),
                        ex.getClass().getSimpleName());
            }
        }
        return handled;
    }

    private int reclaim(
            String streamKey, StreamListener<String, MapRecord<String, String, String>> listener) {
        PendingMessages pending =
                template.opsForStream().pending(streamKey, group, Range.unbounded(), PAGE_SIZE);
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        List<RecordId> idle = new ArrayList<>();
        Map<String, Long> deliveries = new LinkedHashMap<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                idle.add(message.getId());
                deliveries.put(message.getIdAsString(), message.getTotalDeliveryCount());
            }
        }
        if (idle.isEmpty()) {
            return 0;
        }
        List<MapRecord<String, Object, Object>> claimed =
                template.opsForStream()
                        .claim(
                                streamKey,
                                group,
                                consumerName,
                                XClaimOptions.minIdle(minIdle).ids(idle));
        int handled = 0;
        for (MapRecord<String, Object, Object> record : claimed) {
            long delivered = deliveries.getOrDefault(record.getId().getValue(), 1L);
            if (delivered > maxDeliveries) {
                deadLetter(streamKey, record);
                continue;
            }
            log.info(
                    "[RedisStream] {} 방치된 메시지 {} 를 다시 처리합니다 (배달 {}회째 시도)",
                    streamKey,
                    record.getId().getValue(),
                    delivered + 1);
            try {
                listener.onMessage(toStringRecord(streamKey, record));
                handled++;
            } catch (RuntimeException ex) {
                log.warn(
                        "[RedisStream] {} 메시지 {} 재처리 실패: {}",
                        streamKey,
                        record.getId().getValue(),
                        ex.getClass().getSimpleName());
            }
        }
        return handled;
    }

    private void deadLetter(String streamKey, MapRecord<String, Object, Object> record) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        fields.put("originalId", record.getId().getValue());
        template.opsForStream()
                .add(
                        StreamRecords.string(fields).withStreamKey(streamKey + DEAD_LETTER_SUFFIX),
                        XAddOptions.maxlen(DEAD_LETTER_MAX_LENGTH).approximateTrimming(true));
        template.opsForStream().acknowledge(streamKey, group, record.getId());
        template.opsForStream().delete(streamKey, record.getId());
        log.error(
                "[RedisStream] {} 메시지 {} 가 {}회 넘게 실패해 {} 로 옮겼습니다",
                streamKey,
                record.getId().getValue(),
                maxDeliveries,
                streamKey + DEAD_LETTER_SUFFIX);
    }

    private static MapRecord<String, String, String> toStringRecord(
            String streamKey, MapRecord<String, Object, Object> record) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        return StreamRecords.newRecord().in(streamKey).withId(record.getId()).ofMap(fields);
    }
}
