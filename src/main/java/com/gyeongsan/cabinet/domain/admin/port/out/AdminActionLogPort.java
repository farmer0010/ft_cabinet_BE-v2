package com.gyeongsan.cabinet.domain.admin.port.out;

import com.gyeongsan.cabinet.domain.admin.model.AdminActionLog;
import com.gyeongsan.cabinet.domain.admin.model.AdminActionLogSummary;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminActionLogPort {

    void save(AdminActionLog log);

    Optional<AdminActionLog> findByBatchId(String batchId);

    Optional<String> findUndoBatchIdOf(String originalBatchId);

    Page<AdminActionLogSummary> findSummaries(Pageable pageable);
}
