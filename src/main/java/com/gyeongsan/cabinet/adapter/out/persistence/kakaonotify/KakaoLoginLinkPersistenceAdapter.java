package com.gyeongsan.cabinet.adapter.out.persistence.kakaonotify;

import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoLoginLinkPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class KakaoLoginLinkPersistenceAdapter implements KakaoLoginLinkPort {

    @PersistenceContext private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findKakaoProviderId(Long userId) {
        List<String> ids =
                entityManager
                        .createQuery(
                                "SELECT l.providerId FROM OauthLink l "
                                        + "WHERE l.user.id = :userId AND l.provider = 'kakao'",
                                String.class)
                        .setParameter("userId", userId)
                        .setMaxResults(1)
                        .getResultList();
        return ids.stream().findFirst();
    }
}
