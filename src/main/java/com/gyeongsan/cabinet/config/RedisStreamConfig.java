package com.gyeongsan.cabinet.config;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

@Configuration
@RequiredArgsConstructor
@Log4j2
public class RedisStreamConfig {

    private final RedisConnectionFactory redisConnectionFactory;
    private final StringRedisTemplate stringRedisTemplate;

    public static final String SLACK_STREAM_KEY = "slack-alarm-stream";
    public static final String LOGTIME_STREAM_KEY = "logtime-sync-stream";
    public static final String CONSUMER_GROUP_NAME = "cabinet-backend-group";
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(2);
    private final String consumerName = UUID.randomUUID().toString();

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            streamMessageListenerContainer(
                    StreamListener<String, MapRecord<String, String, String>>
                            slackAlarmStreamListener,
                    StreamListener<String, MapRecord<String, String, String>>
                            logtimeStreamListener) {
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        @SuppressWarnings("unchecked")
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .serializer(stringSerializer)
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(redisConnectionFactory, options);

        createStreamGroup(SLACK_STREAM_KEY);
        createStreamGroup(LOGTIME_STREAM_KEY);

        subscribe(container, SLACK_STREAM_KEY, guarded(slackAlarmStreamListener));
        subscribe(container, LOGTIME_STREAM_KEY, guarded(logtimeStreamListener));

        container.start();
        return container;
    }

    /**
     * 수령만 하고 ACK 하지 못한 메시지(처리 중 죽은 프로세스의 PEL)를 주기적으로 회수해 같은 리스너로 다시 처리한다. 소비자 이름이 부팅마다 바뀌어 구독은 그
     * 메시지를 다시 읽지 못하기 때문이다.
     */
    @Bean
    public StreamPendingReclaimer streamPendingReclaimer(
            StreamListener<String, MapRecord<String, String, String>> slackAlarmStreamListener,
            StreamListener<String, MapRecord<String, String, String>> logtimeStreamListener,
            @Value("${app.redis-stream.reclaim.min-idle:60s}") Duration minIdle,
            @Value("${app.redis-stream.reclaim.interval:30s}") Duration interval,
            @Value("${app.redis-stream.reclaim.initial-delay:10s}") Duration initialDelay,
            @Value("${app.redis-stream.reclaim.max-deliveries:5}") long maxDeliveries) {
        return new StreamPendingReclaimer(
                stringRedisTemplate,
                CONSUMER_GROUP_NAME,
                consumerName,
                Map.of(
                        SLACK_STREAM_KEY, guarded(slackAlarmStreamListener),
                        LOGTIME_STREAM_KEY, guarded(logtimeStreamListener)),
                minIdle,
                maxDeliveries,
                interval,
                initialDelay);
    }

    private StreamListener<String, MapRecord<String, String, String>> guarded(
            StreamListener<String, MapRecord<String, String, String>> listener) {
        return new InitMarkerSkippingListener(listener, stringRedisTemplate, CONSUMER_GROUP_NAME);
    }

    /**
     * 구독을 등록한다. 기본값(첫 오류에 구독 취소)을 끄고 오류 처리기를 둔다: Redis 연결 끊김이나 스트림 키 삭제로 한 번 실패해도 소비가 영구히 멈추지 않는다.
     */
    private void subscribe(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            String streamKey,
            StreamListener<String, MapRecord<String, String, String>> listener) {
        container.register(
                StreamMessageListenerContainer.StreamReadRequest.builder(
                                StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
                        .consumer(Consumer.from(CONSUMER_GROUP_NAME, consumerName))
                        .autoAcknowledge(false)
                        .cancelOnError(t -> false)
                        .errorHandler(
                                new StreamErrorHandler(
                                        streamKey,
                                        () -> recreateGroupFromStart(streamKey),
                                        ERROR_BACKOFF))
                        .build(),
                listener);
    }

    /**
     * 그룹이 사라졌을 때(스트림 키 삭제, Redis 재시작 등) 남아 있는 메시지를 놓치지 않도록 처음({@code 0})부터 읽는 그룹을 만든다(스트림이 없으면 같이
     * 만든다). 처리가 끝난 메시지는 리스너가 삭제(XDEL)하므로 스트림에 남은 것은 아직 처리되지 않은 메시지뿐이다. 이미 그룹이 있으면(BUSYGROUP) 아무것도
     * 하지 않는다.
     */
    private void recreateGroupFromStart(String streamKey) {
        try {
            stringRedisTemplate.execute(
                    (RedisCallback<String>)
                            connection ->
                                    connection
                                            .streamCommands()
                                            .xGroupCreate(
                                                    stringRedisTemplate
                                                            .getStringSerializer()
                                                            .serialize(streamKey),
                                                    CONSUMER_GROUP_NAME,
                                                    ReadOffset.from("0"),
                                                    true));
        } catch (RuntimeException e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private void createStreamGroup(String streamKey) {
        try {
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(streamKey))) {
                stringRedisTemplate
                        .opsForStream()
                        .add(streamKey, Collections.singletonMap("init", "init"));
                stringRedisTemplate.opsForStream().createGroup(streamKey, CONSUMER_GROUP_NAME);
            } else {
                boolean groupExists =
                        stringRedisTemplate.opsForStream().groups(streamKey).stream()
                                .anyMatch(group -> group.groupName().equals(CONSUMER_GROUP_NAME));
                if (!groupExists) {
                    stringRedisTemplate.opsForStream().createGroup(streamKey, CONSUMER_GROUP_NAME);
                }
            }
        } catch (Exception e) {
            log.warn("Redis Stream 그룹 초기화 예외 (무시 가능): {}", e.getMessage());
        }
    }
}
