package com.gyeongsan.cabinet.config;

import com.gyeongsan.cabinet.application.alarm.SlackReportForwardService;
import com.gyeongsan.cabinet.application.alarm.SlackReportSettings;
import com.gyeongsan.cabinet.domain.alarm.port.in.ForwardSlackReportsUseCase;
import com.gyeongsan.cabinet.domain.alarm.port.out.AlarmPort;
import com.gyeongsan.cabinet.domain.alarm.port.out.ReportCursorPort;
import com.gyeongsan.cabinet.domain.alarm.port.out.ReportRecipientPort;
import com.gyeongsan.cabinet.domain.alarm.port.out.SlackChannelPort;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.slack-report.enabled", havingValue = "true")
public class SlackReportConfig {

    @Bean
    public SlackReportSettings slackReportSettings(
            @Value("${app.slack-report.channel-id:}") String channelId,
            @Value("${app.slack-report.max-per-poll:20}") int maxPerPoll,
            @Value("${app.slack-report.max-text-length:1500}") int maxTextLength) {
        return new SlackReportSettings(channelId.trim(), maxPerPoll, maxTextLength);
    }

    @Bean
    public ForwardSlackReportsUseCase forwardSlackReportsUseCase(
            SlackChannelPort channelPort,
            ReportCursorPort cursorPort,
            AlarmPort alarmPort,
            ReportRecipientPort recipientPort,
            SlackReportSettings settings) {
        return new SlackReportForwardService(
                channelPort, cursorPort, alarmPort, recipientPort, settings, Clock.systemUTC());
    }
}
