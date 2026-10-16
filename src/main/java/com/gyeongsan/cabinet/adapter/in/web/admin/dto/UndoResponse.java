package com.gyeongsan.cabinet.adapter.in.web.admin.dto;

import com.gyeongsan.cabinet.domain.cabinet.model.CabinetStatus;
import java.util.List;

public record UndoResponse(
        String undoBatchId,
        String originalBatchId,
        List<RestoredCabinet> restoredCabinets,
        List<ReopenedLent> reopenedLents) {

    public record RestoredCabinet(
            Long cabinetId, Integer visibleNum, CabinetStatus fromStatus, CabinetStatus toStatus) {}

    public record ReopenedLent(
            Long lentHistoryId, Long cabinetId, Integer visibleNum, Long userId, String userName) {}
}
