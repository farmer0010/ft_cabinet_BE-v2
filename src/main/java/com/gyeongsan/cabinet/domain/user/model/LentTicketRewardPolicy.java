package com.gyeongsan.cabinet.domain.user.model;

public record LentTicketRewardPolicy(int defaultThresholdMinutes, int transcenderThresholdMinutes) {

    public LentTicketRewardPolicy {
        if (defaultThresholdMinutes <= 0 || transcenderThresholdMinutes <= 0) {
            throw new IllegalArgumentException("대여권 지급 기준(분)은 1 이상이어야 합니다.");
        }
        if (transcenderThresholdMinutes > defaultThresholdMinutes) {
            throw new IllegalArgumentException("트센 지급 기준은 일반 기준보다 클 수 없습니다.");
        }
    }

    public int thresholdMinutesFor(User user) {
        return user.isTranscender() ? transcenderThresholdMinutes : defaultThresholdMinutes;
    }

    public boolean qualifies(User user, int logtimeMinutes) {
        return logtimeMinutes >= thresholdMinutesFor(user);
    }

    public boolean isGradeRefreshCandidate(User user, int logtimeMinutes) {
        return !user.isPisciner()
                && !user.isTranscender()
                && logtimeMinutes >= transcenderThresholdMinutes
                && logtimeMinutes < defaultThresholdMinutes;
    }
}
