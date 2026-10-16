package com.gyeongsan.cabinet.domain.kakaonotify.port.out;

import java.util.Optional;

public interface KakaoLoginLinkPort {

    Optional<String> findKakaoProviderId(Long userId);
}
