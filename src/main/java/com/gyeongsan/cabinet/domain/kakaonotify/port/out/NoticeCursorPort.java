package com.gyeongsan.cabinet.domain.kakaonotify.port.out;

import java.util.Optional;

public interface NoticeCursorPort {

    Optional<String> getCursor(String channelId);

    void saveCursor(String channelId, String ts);
}
