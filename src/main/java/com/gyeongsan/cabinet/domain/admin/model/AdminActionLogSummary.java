package com.gyeongsan.cabinet.domain.admin.model;

import java.time.LocalDateTime;

public record AdminActionLogSummary(
        String batchId,
        AdminActionType actionType,
        Long actorId,
        String actorName,
        String reason,
        LocalDateTime createdAt,
        long itemCount,
        String undoOfBatchId,
        String undoneByBatchId) {}
