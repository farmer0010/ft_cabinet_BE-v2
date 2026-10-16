package com.gyeongsan.cabinet.domain.lent.port.out;

import com.gyeongsan.cabinet.domain.user.model.FtCursusEntry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FtApiPort {

    int getLogtimeBetween(String intraId, LocalDateTime start, LocalDateTime end);

    Optional<List<FtCursusEntry>> getCursusEntries(String intraId);
}
