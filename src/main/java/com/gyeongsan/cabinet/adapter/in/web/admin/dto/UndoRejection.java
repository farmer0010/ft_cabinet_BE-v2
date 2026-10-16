package com.gyeongsan.cabinet.adapter.in.web.admin.dto;

import com.gyeongsan.cabinet.domain.admin.model.UndoConflictCode;
import java.util.List;

public record UndoRejection(String undoneByBatchId, List<Conflict> conflicts) {

    public record Conflict(
            UndoConflictCode code,
            Long cabinetId,
            Integer visibleNum,
            Long userId,
            String detail) {}
}
