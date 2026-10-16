package com.gyeongsan.cabinet.adapter.in.scheduler.kakaonotify;

import com.gyeongsan.cabinet.domain.kakaonotify.port.in.ForwardSlackNoticesUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.slack-notice.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log4j2
public class SlackNoticeScheduler {

    private final ForwardSlackNoticesUseCase forwardSlackNotices;

    @Scheduled(
            initialDelayString = "${app.slack-notice.initial-delay-ms:20000}",
            fixedDelayString = "${app.slack-notice.poll-interval-ms:60000}")
    @SchedulerLock(
            name = "slackNoticeForwardTask",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT10S")
    public void poll() {
        try {
            forwardSlackNotices.forwardNewNotices();
        } catch (RuntimeException e) {
            log.error("[SlackNotice] 전달 중 예상치 못한 오류: {}", e.toString(), e);
        }
    }
}
