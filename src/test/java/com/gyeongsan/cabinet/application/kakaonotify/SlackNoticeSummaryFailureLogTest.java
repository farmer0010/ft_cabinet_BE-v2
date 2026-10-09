package com.gyeongsan.cabinet.application.kakaonotify;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gyeongsan.cabinet.adapter.out.crypto.AesGcmTokenCipher;
import com.gyeongsan.cabinet.adapter.out.external.gemini.GeminiNoticeSummarizerAdapter;
import com.gyeongsan.cabinet.application.kakaonotify.SlackNoticeForwardServiceTest.FakeChannel;
import com.gyeongsan.cabinet.application.kakaonotify.SlackNoticeForwardServiceTest.FakeConsents;
import com.gyeongsan.cabinet.application.kakaonotify.SlackNoticeForwardServiceTest.FakeCursor;
import com.gyeongsan.cabinet.application.kakaonotify.SlackNoticeForwardServiceTest.FakeKakao;
import com.gyeongsan.cabinet.domain.alarm.model.SlackChannelMessage;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 실제 Gemini 어댑터가 오류를 내는 경로(로컬 가짜 서버)를 서비스까지 이어서, 실패 로그에 원인(응답 상태)은 남고 응답 본문·API 키·요청 URL·헤더는 남지 않는지
 * 확인한다.
 */
class SlackNoticeSummaryFailureLogTest {

    private static final String API_KEY = "TEST-KEY-should-never-leak-9f8e7d";
    private static final String BODY_MARKER = "RESPONSE-BODY-MARKER-xyz";
    private static final String NOTICE_SECRET = "원문비밀-홍길동";
    private static final String CHANNEL = "C0NOTICE";

    private HttpServer server;
    private volatile int status = 429;
    private volatile String responseBody;
    private volatile long delayMillis = 0;

    private FakeChannel channel;
    private FakeKakao kakao;
    private SlackNoticeForwardService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;
    private Logger adapterLogger;

    @BeforeEach
    void setUp() throws IOException {
        responseBody =
                "{\"error\":{\"code\":429,\"message\":\""
                        + BODY_MARKER
                        + " key="
                        + API_KEY
                        + "\"}}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    try {
                        if (delayMillis > 0) {
                            Thread.sleep(delayMillis);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    try {
                        exchange.sendResponseHeaders(status, out.length);
                        exchange.getResponseBody().write(out);
                    } catch (IOException ignored) {
                        // 클라이언트가 먼저 끊은 경우
                    } finally {
                        exchange.close();
                    }
                });
        server.start();

        channel = new FakeChannel();
        FakeCursor cursor = new FakeCursor();
        FakeConsents consents = new FakeConsents();
        kakao = new FakeKakao();
        cursor.store.put(CHANNEL, "1700000000.000000");

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        AesGcmTokenCipher cipher = new AesGcmTokenCipher(Base64.getEncoder().encodeToString(key));
        FakeConsents.Row row = new FakeConsents.Row();
        row.name = "a";
        row.encrypted = cipher.encrypt("rt-1", "kakao-notify:1");
        consents.rows.put(1L, row);

        service = newService(Duration.ofSeconds(3), cursor, consents, cipher);

        appender = new ListAppender<>();
        appender.start();
        serviceLogger = (Logger) LoggerFactory.getLogger(SlackNoticeForwardService.class);
        adapterLogger = (Logger) LoggerFactory.getLogger(GeminiNoticeSummarizerAdapter.class);
        for (Logger logger : new Logger[] {serviceLogger, adapterLogger}) {
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
        }
    }

    private SlackNoticeForwardService newService(
            Duration timeout, FakeCursor cursor, FakeConsents consents, AesGcmTokenCipher cipher) {
        GeminiNoticeSummarizerAdapter adapter =
                new GeminiNoticeSummarizerAdapter(
                        GeminiNoticeSummarizerAdapter.createWebClient(
                                "http://127.0.0.1:" + server.getAddress().getPort(), timeout),
                        "gemini-test-flash",
                        API_KEY,
                        "low",
                        timeout,
                        CircuitBreaker.ofDefaults("t"));
        return new SlackNoticeForwardService(
                channel,
                cursor,
                consents,
                kakao,
                cipher,
                new SlackNoticeSettings(CHANNEL, 3, 24, "https://front.example"),
                Clock.fixed(Instant.ofEpochSecond(1_700_000_500L), ZoneOffset.UTC),
                adapter);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serviceLogger.detachAppender(appender);
        adapterLogger.detachAppender(appender);
    }

    private void postLongNotice() {
        channel.messages.add(
                new SlackChannelMessage(
                        "1700000100.000100", "U1", NOTICE_SECRET + "가".repeat(300), null, 0, 0));
    }

    private String allLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : appender.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) {
                sb.append(e.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    private void assertNothingSensitiveLogged() {
        assertThat(allLogs())
                .doesNotContain(
                        API_KEY,
                        BODY_MARKER,
                        NOTICE_SECRET,
                        "127.0.0.1",
                        "generateContent",
                        "x-goog-api-key",
                        "/v1beta");
    }

    @Test
    @DisplayName("HTTP 429 면 WARN 에 'Gemini 응답 상태 429' 가 남고, 응답 본문·키·URL·헤더는 남지 않는다")
    void status429IsLoggedWithoutSensitiveData() {
        postLongNotice();

        service.forwardNewNotices();

        assertThat(kakao.sent).as("요약이 실패해도 자르기로 발송된다").hasSize(1);
        assertThat(allLogs()).contains("공지 요약에 실패해 자르기로 대체합니다").contains("Gemini 응답 상태 429");
        assertNothingSensitiveLogged();
    }

    @Test
    @DisplayName("HTTP 500 / 403 도 상태 코드가 원인으로 남는다")
    void otherStatusCodesAreLogged() {
        status = 500;
        postLongNotice();

        service.forwardNewNotices();

        assertThat(allLogs()).contains("Gemini 응답 상태 500");
        assertNothingSensitiveLogged();
    }

    @Test
    @DisplayName("시간 초과도 원인('Gemini 호출에 실패했습니다')이 남고 URL 은 남지 않는다")
    void timeoutIsLoggedWithoutSensitiveData() throws Exception {
        delayMillis = 3_000;
        FakeCursor cursor = new FakeCursor();
        cursor.store.put(CHANNEL, "1700000000.000000");
        FakeConsents consents = new FakeConsents();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        AesGcmTokenCipher cipher = new AesGcmTokenCipher(Base64.getEncoder().encodeToString(key));
        FakeConsents.Row row = new FakeConsents.Row();
        row.name = "a";
        row.encrypted = cipher.encrypt("rt-1", "kakao-notify:1");
        consents.rows.put(1L, row);
        service = newService(Duration.ofMillis(500), cursor, consents, cipher);
        postLongNotice();

        service.forwardNewNotices();

        assertThat(kakao.sent).hasSize(1);
        assertThat(allLogs()).contains("Gemini 호출에 실패했습니다");
        assertNothingSensitiveLogged();
    }
}
