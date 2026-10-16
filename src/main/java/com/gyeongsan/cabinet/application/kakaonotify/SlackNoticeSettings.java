package com.gyeongsan.cabinet.application.kakaonotify;

public record SlackNoticeSettings(
        String channelId,
        int maxPerPoll,
        int maxHoldHours,
        String linkUrl,
        boolean permalinkEnabled) {

    public SlackNoticeSettings(String channelId, int maxPerPoll, int maxHoldHours, String linkUrl) {
        this(channelId, maxPerPoll, maxHoldHours, linkUrl, false);
    }

    public SlackNoticeSettings {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("공지 채널 ID(SLACK_NOTICE_CHANNEL_ID)가 필요합니다.");
        }
        if (maxPerPoll <= 0 || maxHoldHours <= 0) {
            throw new IllegalArgumentException("maxPerPoll, maxHoldHours 는 1 이상이어야 합니다.");
        }
        if (linkUrl == null || !(linkUrl.startsWith("https://") || linkUrl.startsWith("http://"))) {
            throw new IllegalArgumentException(
                    "카톡 메시지 링크(SLACK_NOTICE_LINK_URL 또는 FRONTEND_URL)는 http(s) 주소여야 합니다.");
        }
    }
}
