package com.gyeongsan.cabinet.adapter.in.web.admin.dto;

import com.gyeongsan.cabinet.domain.cabinet.model.CabinetStatus;
import java.util.List;

public record BulkStatusUpdateResponse(
        String batchId, List<UpdatedCabinet> updatedCabinets, List<EndedLent> endedLents) {

    public record UpdatedCabinet(
            Long cabinetId,
            Integer visibleNum,
            CabinetStatus previousStatus,
            CabinetStatus status) {}

    public record EndedLent(
            Long lentHistoryId, Long cabinetId, Integer visibleNum, Long userId, String userName) {}
}
