package com.gyeongsan.cabinet.application.alarm;

public record SlackReportSettings(String channelId, int maxPerPoll, int maxTextLength) {

    public SlackReportSettings {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("오류제보 채널 ID(SLACK_REPORT_CHANNEL_ID)가 필요합니다.");
        }
        if (maxPerPoll <= 0 || maxTextLength <= 0) {
            throw new IllegalArgumentException("maxPerPoll, maxTextLength 는 1 이상이어야 합니다.");
        }
    }
}
