package com.gyeongsan.cabinet.adapter.in.scheduler.alarm;

import com.gyeongsan.cabinet.domain.alarm.port.in.ForwardSlackReportsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.slack-report.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log4j2
public class SlackReportScheduler {

    private final ForwardSlackReportsUseCase forwardSlackReports;

    @Scheduled(
            initialDelayString = "${app.slack-report.initial-delay-ms:15000}",
            fixedDelayString = "${app.slack-report.poll-interval-ms:60000}")
    @SchedulerLock(
            name = "slackReportForwardTask",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT10S")
    public void poll() {
        try {
            forwardSlackReports.forwardNewReports();
        } catch (RuntimeException e) {
            log.error("[SlackReport] 전달 중 예상치 못한 오류: {}", e.toString(), e);
        }
    }
}
