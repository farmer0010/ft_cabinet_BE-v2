package com.gyeongsan.cabinet.domain.kakaonotify.model;

import com.gyeongsan.cabinet.domain.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "KAKAO_NOTIFY_CONSENT",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_kakao_notify_consent_user",
                    columnNames = {"USER_ID"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KakaoNotifyConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "USER_ID", nullable = false)
    private User user;

    @Column(name = "ENCRYPTED_REFRESH_TOKEN", nullable = false, length = 1024)
    private String encryptedRefreshToken;

    @Column(name = "SCOPE", nullable = false, length = 255)
    private String scope;

    @Column(name = "CONSENTED_AT", nullable = false)
    private LocalDateTime consentedAt;

    @Column(name = "REVOKED_AT")
    private LocalDateTime revokedAt;

    public static KakaoNotifyConsent create(
            User user, String encryptedRefreshToken, String scope, LocalDateTime now) {
        KakaoNotifyConsent consent = new KakaoNotifyConsent();
        consent.user = user;
        consent.encryptedRefreshToken = encryptedRefreshToken;
        consent.scope = scope;
        consent.consentedAt = now;
        return consent;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public boolean renew(String encryptedRefreshToken, String scope, LocalDateTime now) {
        boolean reactivated = revokedAt != null;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.scope = scope;
        if (reactivated) {
            this.revokedAt = null;
            this.consentedAt = now;
        }
        return reactivated;
    }
}
