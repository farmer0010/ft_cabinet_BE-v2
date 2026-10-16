package com.gyeongsan.cabinet.domain.kakaonotify.port.out;

import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotifyStatus;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoRecipient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KakaoConsentRepositoryPort {

    boolean grant(Long userId, String encryptedRefreshToken, String scope, LocalDateTime now);

    KakaoNotifyStatus findStatus(Long userId);

    List<KakaoRecipient> findActiveRecipients();

    Optional<KakaoRecipient> findActiveRecipient(Long userId);

    boolean revokeIfTokenUnchanged(Long userId, String expectedEncryptedToken, LocalDateTime now);

    boolean rotateIfTokenUnchanged(
            Long userId, String expectedEncryptedToken, String newEncryptedToken);
}
