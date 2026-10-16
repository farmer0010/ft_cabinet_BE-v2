package com.gyeongsan.cabinet.config;

import com.gyeongsan.cabinet.adapter.out.external.gemini.GeminiNoticeSummarizerAdapter;
import com.gyeongsan.cabinet.application.kakaonotify.SlackNoticeForwardService;
import com.gyeongsan.cabinet.application.kakaonotify.SlackNoticeSettings;
import com.gyeongsan.cabinet.domain.alarm.port.out.SlackChannelPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.in.ForwardSlackNoticesUseCase;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoConsentRepositoryPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoNotificationPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.NoticeCursorPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.NoticeSummarizerPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.TokenCipherPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Log4j2
@Configuration
@ConditionalOnProperty(name = "app.slack-notice.enabled", havingValue = "true")
public class SlackNoticeConfig {

    @Bean
    public SlackNoticeSettings slackNoticeSettings(
            @Value("${app.slack-notice.channel-id:}") String channelId,
            @Value("${app.slack-notice.max-per-poll:5}") int maxPerPoll,
            @Value("${app.slack-notice.max-hold-hours:24}") int maxHoldHours,
            @Value("${app.slack-notice.link-url:}") String linkUrl,
            @Value("${app.slack-notice.permalink-enabled:false}") boolean permalinkEnabled) {
        return new SlackNoticeSettings(
                channelId.trim(), maxPerPoll, maxHoldHours, linkUrl.trim(), permalinkEnabled);
    }

    @Bean
    public NoticeSummarizerPort noticeSummarizerPort(
            @Value("${app.slack-notice.summary.enabled:false}") boolean enabled,
            @Value("${app.slack-notice.summary.api-key:}") String apiKey,
            @Value("${app.slack-notice.summary.model:gemini-3.6-flash}") String model,
            @Value("${app.slack-notice.summary.thinking-level:low}") String thinkingLevel,
            @Value("${app.slack-notice.summary.base-url:https://generativelanguage.googleapis.com}")
                    String baseUrl,
            @Value("${app.slack-notice.summary.timeout:8s}") String timeoutText,
            @Value("${app.slack-notice.summary.circuit-breaker.sliding-window-size:5}")
                    int slidingWindowSize,
            @Value("${app.slack-notice.summary.circuit-breaker.minimum-calls:3}") int minimumCalls,
            @Value("${app.slack-notice.summary.circuit-breaker.failure-rate-threshold:50}")
                    float failureRateThreshold,
            @Value("${app.slack-notice.summary.circuit-breaker.wait-in-open-state:10m}")
                    String waitInOpenStateText) {
        if (!enabled) {
            return NoticeSummarizerPort.disabled();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn(
                    "[SlackNotice] SLACK_NOTICE_SUMMARY_ENABLED=true 이지만 GEMINI_API_KEY 가 없어 긴 공지 요약을 쓰지 않고 자르기로 대체합니다.");
            return NoticeSummarizerPort.disabled();
        }
        Duration timeout = DurationStyle.detectAndParse(timeoutText.trim());
        Duration waitInOpenState = DurationStyle.detectAndParse(waitInOpenStateText.trim());
        CircuitBreaker circuitBreaker =
                CircuitBreaker.of(
                        "noticeSummary",
                        CircuitBreakerConfig.custom()
                                .slidingWindowSize(slidingWindowSize)
                                .minimumNumberOfCalls(minimumCalls)
                                .failureRateThreshold(failureRateThreshold)
                                .waitDurationInOpenState(waitInOpenState)
                                .build());
        log.info("[SlackNotice] 긴 공지 요약을 켭니다 - 모델: {}, 제한 시간: {}", model, timeout);
        return new GeminiNoticeSummarizerAdapter(
                GeminiNoticeSummarizerAdapter.createWebClient(baseUrl, timeout),
                model,
                apiKey,
                thinkingLevel,
                timeout,
                circuitBreaker);
    }

    @Bean
    public ForwardSlackNoticesUseCase forwardSlackNoticesUseCase(
            SlackChannelPort channelPort,
            NoticeCursorPort cursorPort,
            KakaoConsentRepositoryPort consentRepository,
            ObjectProvider<KakaoNotificationPort> kakao,
            ObjectProvider<TokenCipherPort> cipher,
            SlackNoticeSettings settings,
            NoticeSummarizerPort summarizer) {
        KakaoNotificationPort kakaoPort = kakao.getIfAvailable();
        TokenCipherPort cipherPort = cipher.getIfAvailable();
        if (kakaoPort == null || cipherPort == null) {
            throw new IllegalStateException(
                    "SLACK_NOTICE_FORWARD_ENABLED=true 이면 KAKAO_NOTIFY_ENABLED=true(와 KAKAO_TOKEN_ENC_KEY)도 필요합니다.");
        }
        return new SlackNoticeForwardService(
                channelPort,
                cursorPort,
                consentRepository,
                kakaoPort,
                cipherPort,
                settings,
                Clock.systemDefaultZone(),
                summarizer);
    }
}
