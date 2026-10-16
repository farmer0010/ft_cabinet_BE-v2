package com.gyeongsan.cabinet.adapter.in.web.admin.dto;

import com.gyeongsan.cabinet.domain.admin.model.AdminActionLogSummary;
import com.gyeongsan.cabinet.domain.admin.model.AdminActionType;
import java.time.LocalDateTime;

public record AdminActionLogSummaryResponse(
        String batchId,
        AdminActionType actionType,
        Long actorId,
        String actorName,
        String reason,
        LocalDateTime createdAt,
        long itemCount,
        String undoOfBatchId,
        String undoneByBatchId) {

    public static AdminActionLogSummaryResponse from(AdminActionLogSummary summary) {
        return new AdminActionLogSummaryResponse(
                summary.batchId(),
                summary.actionType(),
                summary.actorId(),
                summary.actorName(),
                summary.reason(),
                summary.createdAt(),
                summary.itemCount(),
                summary.undoOfBatchId(),
                summary.undoneByBatchId());
    }
}
