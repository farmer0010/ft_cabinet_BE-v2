package com.gyeongsan.cabinet.adapter.out.persistence.kakaonotify;

import com.gyeongsan.cabinet.adapter.out.persistence.user.UserRepository;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotifyConsent;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotifyStatus;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoRecipient;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoConsentRepositoryPort;
import com.gyeongsan.cabinet.domain.user.model.User;
import com.gyeongsan.cabinet.global.exception.ErrorCode;
import com.gyeongsan.cabinet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class KakaoConsentPersistenceAdapter implements KakaoConsentRepositoryPort {

    private final KakaoNotifyConsentRepository consentRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public boolean grant(
            Long userId, String encryptedRefreshToken, String scope, LocalDateTime now) {
        User user =
                userRepository
                        .findByIdWithLock(userId)
                        .orElseThrow(() -> new ServiceException(ErrorCode.USER_NOT_FOUND));

        Optional<KakaoNotifyConsent> existing = consentRepository.findByUserId(userId);
        boolean activated;
        if (existing.isEmpty()) {
            consentRepository.save(
                    KakaoNotifyConsent.create(user, encryptedRefreshToken, scope, now));
            activated = true;
        } else {
            activated = existing.get().renew(encryptedRefreshToken, scope, now);
        }

        if (activated) {
            user.updateKakaoAlarm(true);
        }
        return activated;
    }

    @Override
    @Transactional(readOnly = true)
    public KakaoNotifyStatus findStatus(Long userId) {
        boolean consented =
                consentRepository
                        .findByUserId(userId)
                        .map(KakaoNotifyConsent::isActive)
                        .orElse(false);
        boolean alarm = userRepository.findById(userId).map(User::isKakaoAlarm).orElse(false);
        return new KakaoNotifyStatus(consented, alarm);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KakaoRecipient> findActiveRecipients() {
        return consentRepository.findActiveRecipients();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KakaoRecipient> findActiveRecipient(Long userId) {
        return consentRepository.findActiveRecipient(userId);
    }

    @Override
    @Transactional
    public boolean revokeIfTokenUnchanged(
            Long userId, String expectedEncryptedToken, LocalDateTime now) {
        return consentRepository.revokeIfTokenUnchanged(userId, expectedEncryptedToken, now) > 0;
    }

    @Override
    @Transactional
    public boolean rotateIfTokenUnchanged(
            Long userId, String expectedEncryptedToken, String newEncryptedToken) {
        return consentRepository.rotateIfTokenUnchanged(
                        userId, expectedEncryptedToken, newEncryptedToken)
                > 0;
    }
}
