package com.gyeongsan.cabinet.adapter.in.web.admin.dto;

import java.util.List;

public record BulkStatusRejection(
        List<Long> missingCabinetIds, List<OccupiedCabinet> occupiedCabinets) {

    public record OccupiedCabinet(
            Long cabinetId, Integer visibleNum, Long userId, String userName) {}
}
