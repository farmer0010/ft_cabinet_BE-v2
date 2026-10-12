package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gyeongsan.cabinet.support.RedisTestSupport;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 감사 H-7: Redis Streams 구독이 첫 오류에 영구히 취소되고, 재시작 전에 수령만 하고 처리하지 못한 메시지는 아무도 다시 읽지 않는다. 실제 Redis 와 실제
 * {@link RedisStreamConfig} 로 확인한다. Docker 가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisStreamResilienceRedisTest {

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    private final List<String> received = new CopyOnWriteArrayList<>();
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    @BeforeAll
    static void startRedis() {
        redis = RedisTestSupport.newRedis();
        redis.start();
        factory = RedisTestSupport.connectionFactory(redis);
        template = RedisTestSupport.template(factory);
    }

    @AfterAll
    static void stopRedis() {
        if (factory != null) {
            factory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void flush() {
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
        received.clear();
    }

    @AfterEach
    void stopContainer() throws InterruptedException {
        if (reclaimer != null) {
            reclaimer.destroy();
            reclaimer = null;
        }
        if (container != null) {
            container.stop();
            container = null;
            // 구독 스레드가 마지막 폴링/오류 처리(최대 대기 1초 + 쉬는 시간 2초)를 끝내고 빠질 때까지 기다린다. 남은 스레드가 다음 테스트의
            // Redis 상태(그룹 재생성 등)를 건드리지 않게 하려는 격리용이다.
            Thread.sleep(3_500);
        }
    }

    /** 운영 리스너처럼 처리 뒤 스스로 ACK 하고 삭제한다. */
    private StreamListener<String, MapRecord<String, String, String>> recording() {
        return message -> {
            received.add(message.getValue().get("n"));
            ack(message);
        };
    }

    private static void ack(MapRecord<String, String, String> message) {
        template.opsForStream()
                .acknowledge(
                        message.getStream(),
                        RedisStreamConfig.CONSUMER_GROUP_NAME,
                        message.getId());
        template.opsForStream().delete(message.getStream(), message.getId());
    }

    private StreamPendingReclaimer reclaimer;

    private void startContainer() {
        startContainer(recording());
    }

    private void startContainer(StreamListener<String, MapRecord<String, String, String>> slack) {
        RedisStreamConfig config = new RedisStreamConfig(factory, template);
        container = config.streamMessageListenerContainer(slack, message -> {});
        // 테스트에서는 방치 기준을 짧게 한다(운영 기본값 60초).
        reclaimer =
                config.streamPendingReclaimer(
                        slack,
                        message -> {},
                        Duration.ofMillis(500),
                        Duration.ofMillis(300),
                        Duration.ofMillis(100),
                        2);
        reclaimer.afterPropertiesSet();
    }

    private static void send(String n) {
        template.opsForStream()
                .add(RedisStreamConfig.SLACK_STREAM_KEY, Collections.singletonMap("n", n));
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("기준선: 정상 상태에서는 보낸 메시지를 소비자가 받는다")
    void receivesMessages() throws Exception {
        startContainer();
        send("1");
        assertThat(waitFor(() -> received.contains("1"), 5_000)).isTrue();
    }

    @Test
    @DisplayName("Redis 쪽 오류(스트림 키가 사라져 그룹 없음)가 한 번 나도 구독이 유지돼, 복구된 뒤의 메시지를 받는다")
    void subscriptionSurvivesRedisSideError() throws Exception {
        startContainer();
        send("1");
        assertThat(waitFor(() -> received.contains("1"), 5_000)).isTrue();

        // 스트림이 사라지면(flush, 장애 복구 등) 다음 읽기가 NOGROUP 오류로 실패한다.
        template.delete(RedisStreamConfig.SLACK_STREAM_KEY);
        Thread.sleep(2_500); // 폴링이 오류를 만나도록 둔다.

        // 운영자/재기동이 스트림과 그룹을 되살렸다고 가정한다.
        template.opsForStream()
                .add(RedisStreamConfig.SLACK_STREAM_KEY, Collections.singletonMap("init", "init"));
        createGroupQuietly();
        send("2");

        assertThat(waitFor(() -> received.contains("2"), 10_000))
                .as("오류 뒤에도 구독이 살아 있어야 한다 (받은 것: %s)", received)
                .isTrue();
    }

    @Test
    @DisplayName("그룹이 사라진 뒤 스트림에 메시지가 쌓여도, 스스로 그룹을 되살려 그 메시지를 받는다")
    void recreatesMissingGroupAndDeliversMessageAddedMeanwhile() throws Exception {
        startContainer();
        send("1");
        assertThat(waitFor(() -> received.contains("1"), 5_000)).isTrue();

        template.delete(RedisStreamConfig.SLACK_STREAM_KEY);
        send("2"); // XADD 가 스트림을 새로 만들지만 그룹은 없다.

        assertThat(waitFor(() -> received.contains("2"), 15_000))
                .as("그룹이 복구되고 그 사이 쌓인 메시지도 전달돼야 한다 (받은 것: %s)", received)
                .isTrue();
    }

    @Test
    @DisplayName("이전 프로세스가 수령만 하고 ACK 하지 못한 메시지는 재시작한 소비자가 다시 처리한다")
    void pendingMessageOfDeadConsumerIsReprocessed() throws Exception {
        // 이전 프로세스: 그룹을 만들고 메시지를 수령(XREADGROUP)했지만 처리/ACK 전에 죽었다.
        template.opsForStream()
                .add(RedisStreamConfig.SLACK_STREAM_KEY, Collections.singletonMap("init", "init"));
        createGroupQuietly();
        send("crashed");
        List<MapRecord<String, Object, Object>> delivered =
                template.opsForStream()
                        .read(
                                Consumer.from(
                                        RedisStreamConfig.CONSUMER_GROUP_NAME, "dead-consumer"),
                                StreamReadOptions.empty().count(10),
                                StreamOffset.create(
                                        RedisStreamConfig.SLACK_STREAM_KEY,
                                        ReadOffset.lastConsumed()));
        assertThat(delivered).hasSize(1);
        Map<String, Long> pending = pendingByConsumer();
        assertThat(pending).containsEntry("dead-consumer", 1L);

        // 새 프로세스가 뜬다(소비자 이름이 새로 만들어진다).
        startContainer();

        assertThat(waitFor(() -> received.contains("crashed"), 8_000))
                .as("수령만 하고 죽은 메시지가 재처리돼야 한다 (받은 것: %s)", received)
                .isTrue();
    }

    @Test
    @DisplayName("그룹이 사라지며 남은 init 표식 레코드는 소비자에게 전달되지 않는다")
    void initMarkerIsNotDeliveredToListener() throws Exception {
        startContainer();
        send("1");
        assertThat(waitFor(() -> received.contains("1"), 5_000)).isTrue();

        template.delete(RedisStreamConfig.SLACK_STREAM_KEY);
        template.opsForStream()
                .add(RedisStreamConfig.SLACK_STREAM_KEY, Collections.singletonMap("init", "init"));
        send("3");

        assertThat(waitFor(() -> received.contains("3"), 15_000)).isTrue();
        assertThat(received).as("init 표식은 리스너에 가지 않는다").doesNotContainNull();
    }

    @Test
    @DisplayName("방치 시간이 기준보다 짧은(처리 중일 수 있는) 메시지는 가져가지 않는다")
    void freshPendingMessageIsNotStolen() throws Exception {
        prepareGroupAndDeliverToDeadConsumer("in-flight");
        RedisStreamConfig config = new RedisStreamConfig(factory, template);
        StreamPendingReclaimer longIdle =
                config.streamPendingReclaimer(
                        recording(),
                        message -> {},
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(60),
                        5);

        assertThat(longIdle.reclaimOnce()).isZero();
        assertThat(received).isEmpty();
        assertThat(pendingByConsumer()).containsEntry("dead-consumer", 1L);
    }

    @Test
    @DisplayName("계속 실패하는 메시지는 배달 한도를 넘으면 dead 스트림으로 옮기고 PEL 에서 뺀다")
    void poisonMessageIsMovedToDeadStream() throws Exception {
        prepareGroupAndDeliverToDeadConsumer("poison");
        AtomicInteger attempts = new AtomicInteger();
        startContainer(
                message -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("처리 실패");
                });

        assertThat(
                        waitFor(
                                () ->
                                        Boolean.TRUE.equals(
                                                template.hasKey(
                                                        RedisStreamConfig.SLACK_STREAM_KEY
                                                                + StreamPendingReclaimer
                                                                        .DEAD_LETTER_SUFFIX)),
                                10_000))
                .as("시도 횟수 %s", attempts.get())
                .isTrue();
        assertThat(pendingByConsumer()).as("PEL 에서 빠졌다").isEmpty();
        assertThat(attempts.get()).as("한도(2) 안에서만 재시도").isBetween(1, 3);
        var dead =
                template.opsForStream()
                        .range(
                                RedisStreamConfig.SLACK_STREAM_KEY
                                        + StreamPendingReclaimer.DEAD_LETTER_SUFFIX,
                                org.springframework.data.domain.Range.unbounded());
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).getValue()).containsEntry("n", "poison");
    }

    private void prepareGroupAndDeliverToDeadConsumer(String n) {
        template.opsForStream()
                .add(RedisStreamConfig.SLACK_STREAM_KEY, Collections.singletonMap("init", "init"));
        createGroupQuietly();
        send(n);
        template.opsForStream()
                .read(
                        Consumer.from(RedisStreamConfig.CONSUMER_GROUP_NAME, "dead-consumer"),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(
                                RedisStreamConfig.SLACK_STREAM_KEY, ReadOffset.lastConsumed()));
    }

    /** 구독이 스스로 그룹을 되살렸을 수도 있으므로, 이미 있으면(BUSYGROUP) 넘어간다. */
    private static void createGroupQuietly() {
        try {
            template.opsForStream()
                    .createGroup(
                            RedisStreamConfig.SLACK_STREAM_KEY,
                            ReadOffset.latest(),
                            RedisStreamConfig.CONSUMER_GROUP_NAME);
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private static Map<String, Long> pendingByConsumer() {
        var summary =
                template.opsForStream()
                        .pending(
                                RedisStreamConfig.SLACK_STREAM_KEY,
                                RedisStreamConfig.CONSUMER_GROUP_NAME);
        return summary == null ? Map.of() : summary.getPendingMessagesPerConsumer();
    }
}
