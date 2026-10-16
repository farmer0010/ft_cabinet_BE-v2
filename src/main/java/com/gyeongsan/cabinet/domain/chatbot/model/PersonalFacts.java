package com.gyeongsan.cabinet.domain.chatbot.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public sealed interface PersonalFacts {

    record LentExpiry(
            boolean lentActive,
            Integer visibleNum,
            LocalDateTime expiredAt,
            Integer daysRemaining,
            Boolean overdue)
            implements PersonalFacts {}

    record PenaltyStatus(int penaltyDays, LocalDate releaseDate) implements PersonalFacts {}

    record TicketCondition(
            int thresholdMinutes,
            int monthlyLogtimeMinutes,
            boolean meetsThreshold,
            boolean hasUnusedTicket,
            LocalDate nextPayDate)
            implements PersonalFacts {}

    enum Blocker {
        PENALTY,
        ALREADY_LENDING,
        NO_TICKET
    }

    enum AllowedCabinets {
        LAPISCINE_ONLY,
        EXCEPT_LAPISCINE
    }

    record LentBlockers(
            List<Blocker> blockers,
            int penaltyDays,
            LocalDate penaltyReleaseDate,
            Integer activeVisibleNum,
            AllowedCabinets allowedCabinets)
            implements PersonalFacts {}
}
