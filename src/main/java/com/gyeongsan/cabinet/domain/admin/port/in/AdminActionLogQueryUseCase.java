package com.gyeongsan.cabinet.domain.admin.port.in;

import com.gyeongsan.cabinet.adapter.in.web.admin.dto.AdminActionLogDetailResponse;
import com.gyeongsan.cabinet.adapter.in.web.admin.dto.AdminActionLogSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminActionLogQueryUseCase {

    Page<AdminActionLogSummaryResponse> getActionLogs(Pageable pageable);

    AdminActionLogDetailResponse getActionLog(String batchId);
}
