package com.gyeongsan.cabinet.adapter.out.persistence.admin;

import com.gyeongsan.cabinet.domain.admin.model.AdminActionType;
import java.time.LocalDateTime;

interface AdminActionLogSummaryView {

    String getBatchId();

    AdminActionType getActionType();

    Long getActorId();

    String getActorName();

    String getReason();

    LocalDateTime getCreatedAt();

    String getUndoOfBatchId();

    Long getItemCount();

    String getUndoneByBatchId();
}
