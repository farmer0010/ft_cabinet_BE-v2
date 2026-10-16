package com.gyeongsan.cabinet.adapter.in.web.lent.dto;

import com.gyeongsan.cabinet.domain.lent.model.LentReturnResult;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class LentReturnResponse {

    private final String message;

    private final LocalDateTime expiredAtIso;

    private final int daysRemaining;

    private final boolean overdue;

    private final int penaltyAppliedDays;

    private final LocalDateTime returnedAt;

    private LentReturnResponse(String message, LentReturnResult result) {
        this.message = message;
        this.expiredAtIso = result.expiredAt();
        this.daysRemaining = result.daysRemaining();
        this.overdue = result.overdue();
        this.penaltyAppliedDays = result.penaltyAppliedDays();
        this.returnedAt = result.returnedAt();
    }

    public static LentReturnResponse of(String message, LentReturnResult result) {
        return new LentReturnResponse(message, result);
    }
}
