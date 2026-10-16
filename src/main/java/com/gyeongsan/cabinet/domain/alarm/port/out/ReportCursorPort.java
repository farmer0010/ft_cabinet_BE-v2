package com.gyeongsan.cabinet.domain.alarm.port.out;

import java.util.Optional;

public interface ReportCursorPort {

    Optional<String> getCursor(String channelId);

    void saveCursor(String channelId, String ts);
}
