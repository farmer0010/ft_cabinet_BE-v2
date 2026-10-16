package com.gyeongsan.cabinet.domain.kakaonotify.port.out;

import java.util.Optional;

public interface NoticeSummarizerPort {

    Optional<String> summarize(String text, int maxChars);

    static NoticeSummarizerPort disabled() {
        return (text, maxChars) -> Optional.empty();
    }
}
