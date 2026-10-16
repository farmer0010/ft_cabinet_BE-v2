package com.gyeongsan.cabinet.domain.lent.model;

import java.time.LocalDateTime;

public record LentReturnResult(
        LocalDateTime expiredAt,
        int daysRemaining,
        boolean overdue,
        int penaltyAppliedDays,
        LocalDateTime returnedAt) {}
