package com.gyeongsan.cabinet.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class SlackBotServiceTimeoutTest {

    private static final String TOKEN = "TEST-SLACK-TOKEN-should-not-leak-1a2b3c";
    private static final Path MAIN_YML = Path.of("src/main/resources/application.yml");

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile boolean hang;
    private volatile String usersListBody =
            "{\"ok\":true,\"members\":[{\"id\":\"U123\",\"name\":\"jooyoung\"}]}";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    paths.add(exchange.getRequestURI().getPath());
                    authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getRequestBody().readAllBytes();
                    if (hang) {
                        try {
                            release.await(15, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        exchange.close();
                        return;
                    }
                    String body =
                            exchange.getRequestURI().getPath().endsWith("/users.list")
                                    ? usersListBody
                                    : "{\"ok\":true}";
                    byte[] out = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                    exchange.close();
                });
        server.start();

        logger = (Logger) LoggerFactory.getLogger(SlackBotService.class);
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        logger.detachAppender(appender);
    }

    private SlackBotService service(int connectMs, int readMs) {
        return new SlackBotService(
                TOKEN,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                connectMs,
                readMs);
    }

    private String logs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : appender.list) {
            sb.append(e.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("Slack 이 응답하지 않으면 읽기 타임아웃 안에 sendDm 이 끝나고, 예외를 밖으로 던지지 않는다")
    void sendDmReturnsWhenSlackHangs() {
        hang = true;
        SlackBotService service = service(500, 500);

        long start = System.nanoTime();
        service.sendDm("jooyoung", "안녕하세요");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("타임아웃(500ms) 안에 돌아와야 한다").isLessThan(5_000);
        assertThat(paths).as("요청은 실제로 나갔다").isNotEmpty();
        assertThat(logs()).contains("슬랙");
    }

    @Test
    @DisplayName("CSV 없이 users.list 로 찾는 경로도 멈춘 Slack 앞에서 타임아웃 안에 끝난다")
    void usersListLookupAlsoTimesOut() {
        hang = true;

        long start = System.nanoTime();
        service(500, 500).sendDm("not-in-csv", "x");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(5_000);
        assertThat(paths).contains("/api/users.list");
    }

    @Test
    @DisplayName("연결을 받아 주지 않는 주소(블랙홀)에서도 연결 타임아웃 안에 끝난다")
    void connectTimeoutBoundsUnreachableHost() {
        SlackBotService service = new SlackBotService(TOKEN, "http://192.0.2.1/api", 500, 500);

        long start = System.nanoTime();
        service.sendDm("jooyoung", "x");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    @DisplayName("정상 응답이면 users.list 로 Slack ID 를 찾아 chat.postMessage 로 보내고, Bearer 토큰은 헤더로만 간다")
    void happyPathStillWorks() {
        service(500, 2_000).sendDm("jooyoung", "안녕하세요");

        assertThat(paths).containsExactly("/api/users.list", "/api/chat.postMessage");
        assertThat(authHeaders).containsOnly("Bearer " + TOKEN);
        assertThat(logs()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("실패 경로의 로그에도 토큰이 남지 않는다")
    void tokenNeverInLogsOnFailure() {
        hang = true;

        service(300, 300).sendDm("jooyoung", "x");

        assertThat(logs()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("타임아웃이 0 이하(무제한)면 부팅 시점에 거부된다")
    void nonPositiveTimeoutsAreRejected() {
        assertThatThrownBy(() -> new SlackBotService(TOKEN, "http://x/api", 0, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("무제한")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
        assertThatThrownBy(() -> new SlackBotService(TOKEN, "http://x/api", 1000, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("application.yml 의 기본값(3초/5초)과 환경변수 덮어쓰기가 실제 RestTemplate 에 적용된다")
    void timeoutsComeFromConfiguration() {
        ApplicationContextRunner base =
                new ApplicationContextRunner()
                        .withUserConfiguration(SlackBotService.class)
                        .withInitializer(
                                context -> {
                                    try {
                                        for (PropertySource<?> source :
                                                new YamlPropertySourceLoader()
                                                        .load(
                                                                "main-application-yml",
                                                                new FileSystemResource(MAIN_YML))) {
                                            context.getEnvironment()
                                                    .getPropertySources()
                                                    .addLast(source);
                                        }
                                    } catch (IOException e) {
                                        throw new IllegalStateException(e);
                                    }
                                })
                        .withPropertyValues("SLACK_BOT_TOKEN=" + TOKEN);

        base.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    SimpleClientHttpRequestFactory factory =
                            factoryOf(context.getBean(SlackBotService.class));
                    assertThat(ReflectionTestUtils.getField(factory, "connectTimeout"))
                            .isEqualTo(3000);
                    assertThat(ReflectionTestUtils.getField(factory, "readTimeout"))
                            .isEqualTo(5000);
                });

        base.withPropertyValues(
                        "SLACK_HTTP_CONNECT_TIMEOUT_MS=1111", "SLACK_HTTP_READ_TIMEOUT_MS=2222")
                .run(
                        context -> {
                            SimpleClientHttpRequestFactory factory =
                                    factoryOf(context.getBean(SlackBotService.class));
                            assertThat(ReflectionTestUtils.getField(factory, "connectTimeout"))
                                    .isEqualTo(1111);
                            assertThat(ReflectionTestUtils.getField(factory, "readTimeout"))
                                    .isEqualTo(2222);
                        });

        base.withPropertyValues("SLACK_HTTP_READ_TIMEOUT_MS=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private static SimpleClientHttpRequestFactory factoryOf(SlackBotService service) {
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        return (SimpleClientHttpRequestFactory) restTemplate.getRequestFactory();
    }
}
