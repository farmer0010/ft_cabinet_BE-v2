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
