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

/**
 * 수령(XREADGROUP)만 하고 ACK 하지 못한 메시지를 다시 처리한다.
 *
 * <p>소비자 이름이 부팅마다 새 UUID 이고 구독이 {@code lastConsumed}({@code >}) 로만 읽으므로, 처리 중 죽은 프로세스가 수령해 둔
 * 메시지(PEL)는 아무도 다시 읽지 않았다. 이 클래스가 일정 시간(minIdle) 넘게 방치된 PEL 항목을 어느 소비자 것이든 현재 소비자로 가져와({@code
 * XCLAIM}) 같은 리스너로 다시 처리한다.
 *
 * <ul>
 *   <li>XCLAIM 의 min-idle 조건은 서버 안에서 원자적이라, 여러 인스턴스가 동시에 시도해도 한 곳만 가져가고 정상 처리 중인 메시지(방치 시간이 짧음)는
 *       건드리지 않는다.
 *   <li>리스너는 처리를 마치면 스스로 ACK·삭제한다. ACK 하지 않고 실패하는 메시지는 배달 횟수가 {@code maxDeliveries} 를 넘으면
 *       '{스트림}:dead' 스트림으로 옮기고 ACK·삭제해, 같은 메시지가 영원히 재시도되지 않게 한다.
 *   <li>적어도 한 번(at-least-once) 처리이므로 리스너는 같은 메시지를 두 번 받아도 안전해야 한다.
 * </ul>
 */
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
            // 다음 주기에 다시 시도한다. 스케줄러가 예외로 멈추지 않게 여기서 끊는다.
            log.warn("[RedisStream] 방치된 메시지 회수 중 오류: {}", e.getClass().getSimpleName());
        }
    }

    /** 모든 스트림에서 방치된 메시지를 한 번 회수해 처리한다. 처리한 개수를 돌려준다. */
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
                // ACK 되지 않은 채 남아 다음 주기에 다시 시도되고, 한도를 넘으면 dead 로 옮겨진다.
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
        // 본문(사용자 ID·DM 내용)은 로그에 남기지 않는다.
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
