package com.gyeongsan.cabinet.application.kakaonotify;

import static org.assertj.core.api.Assertions.assertThat;

import com.gyeongsan.cabinet.adapter.out.crypto.AesGcmTokenCipher;
import com.gyeongsan.cabinet.domain.alarm.model.SlackChannelException;
import com.gyeongsan.cabinet.domain.alarm.model.SlackChannelMessage;
import com.gyeongsan.cabinet.domain.alarm.model.SlackHistory;
import com.gyeongsan.cabinet.domain.alarm.port.out.SlackChannelPort;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoGrant;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoInsufficientScopeException;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoInvalidGrantException;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoMessage;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotificationException;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotifyStatus;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoRecipient;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoRefreshedToken;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoConsentRepositoryPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoNotificationPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.NoticeCursorPort;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlackNoticeForwardServiceTest {

    private static final String CHANNEL = "C0NOTICE";
    private static final long NOW = 1_700_000_500L;

    static class FakeChannel implements SlackChannelPort {
        final List<SlackChannelMessage> messages = new ArrayList<>();
        boolean failFetch;
        boolean truncated;

        @Override
        public SlackHistory fetchNewerThan(String channelId, String oldestTs) {
            if (failFetch) {
                throw new SlackChannelException("boom");
            }
            BigDecimal oldest = new BigDecimal(oldestTs);
            return new SlackHistory(
                    messages.stream()
                            .filter(m -> new BigDecimal(m.ts()).compareTo(oldest) > 0)
                            .toList(),
                    truncated);
        }

        @Override
        public Optional<String> latestTs(String channelId) {
            return messages.stream().map(SlackChannelMessage::ts).reduce((a, b) -> b);
        }

        /** ts → 퍼머링크. 비어 있으면 퍼머링크를 못 얻는 상황. */
        final Map<String, String> permalinks = new HashMap<>();

        RuntimeException permalinkFailure;
        final List<String> permalinkCalls = new ArrayList<>();

        @Override
        public Optional<String> permalink(String channelId, String ts) {
            permalinkCalls.add(channelId + "/" + ts);
            if (permalinkFailure != null) {
                throw permalinkFailure;
            }
            return Optional.ofNullable(permalinks.get(ts));
        }
    }

    static class FakeCursor implements NoticeCursorPort {
        final Map<String, String> store = new HashMap<>();
        boolean failRead;

        @Override
        public Optional<String> getCursor(String channelId) {
            if (failRead) {
                throw new IllegalStateException("redis down");
            }
            return Optional.ofNullable(store.get(channelId));
        }

        @Override
        public void saveCursor(String channelId, String ts) {
            store.put(channelId, ts);
        }
    }

    /** 동의 테이블과 알림 스위치를 메모리에 흉내 낸다. 토큰 비교 조건(CAS)도 실제 저장소와 같게 동작한다. */
    static class FakeConsents implements KakaoConsentRepositoryPort {
        static final class Row {
            String name;
            String encrypted;
            boolean alarm = true;
            boolean revoked;
            boolean deleted;
        }

        final Map<Long, Row> rows = new LinkedHashMap<>();
        boolean failList;
        boolean failFind;

        /** 수신 대상 목록을 돌려준 직후 실행한다(목록과 발송 직전 재확인 사이의 변화를 흉내 낸다). */
        Runnable afterListHook;

        int listCalls;
        final List<Long> revokedUsers = new ArrayList<>();

        private boolean active(Row r) {
            return !r.revoked && r.alarm && !r.deleted;
        }

        @Override
        public List<KakaoRecipient> findActiveRecipients() {
            listCalls++;
            if (failList) {
                throw new IllegalStateException("db down");
            }
            List<KakaoRecipient> listed =
                    rows.entrySet().stream()
                            .filter(e -> active(e.getValue()))
                            .map(
                                    e ->
                                            new KakaoRecipient(
                                                    e.getKey(),
                                                    e.getValue().name,
                                                    e.getValue().encrypted))
                            .toList();
            if (afterListHook != null) {
                afterListHook.run();
            }
            return listed;
        }

        @Override
        public Optional<KakaoRecipient> findActiveRecipient(Long userId) {
            if (failFind) {
                throw new IllegalStateException("db down");
            }
            Row r = rows.get(userId);
            return r != null && active(r)
                    ? Optional.of(new KakaoRecipient(userId, r.name, r.encrypted))
                    : Optional.empty();
        }

        @Override
        public boolean revokeIfTokenUnchanged(Long userId, String expected, LocalDateTime now) {
            Row r = rows.get(userId);
            if (r == null || r.revoked || !r.encrypted.equals(expected)) {
                return false;
            }
            r.revoked = true;
            revokedUsers.add(userId);
            return true;
        }

        @Override
        public boolean rotateIfTokenUnchanged(Long userId, String expected, String newToken) {
            Row r = rows.get(userId);
            if (r == null || r.revoked || !r.encrypted.equals(expected)) {
                return false;
            }
            r.encrypted = newToken;
            return true;
        }

        @Override
        public boolean grant(Long userId, String enc, String scope, LocalDateTime now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KakaoNotifyStatus findStatus(Long userId) {
            throw new UnsupportedOperationException();
        }
    }

    /** 사용자별로 정해 둔 대로 응답하는 가짜 카카오. 평문 refresh_token 을 키로 동작을 정한다. */
    static class FakeKakao implements KakaoNotificationPort {
        record Sent(String accessToken, KakaoMessage message) {}

        final Map<String, RuntimeException> refreshFailures = new HashMap<>();
        final Map<String, String> rotated = new HashMap<>(); // 평문 refresh → 새 refresh
        final Map<String, RuntimeException> sendFailures = new HashMap<>(); // access → 오류
        final List<String> refreshCalls = new ArrayList<>();
        final List<Sent> sent = new ArrayList<>();

        /** 이 문구가 들어간 메시지는 일시 장애(503)로 실패한다. null 이면 꺼짐. */
        String sendFailureWhenTextContains;

        @Override
        public KakaoGrant exchangeAuthorizationCode(String code, String redirectUri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KakaoRefreshedToken refreshAccessToken(String refreshToken) {
            refreshCalls.add(refreshToken);
            RuntimeException failure = refreshFailures.get(refreshToken);
            if (failure != null) {
                throw failure;
            }
            return new KakaoRefreshedToken("access-of-" + refreshToken, rotated.get(refreshToken));
        }

        @Override
        public void sendToMe(String accessToken, KakaoMessage message) {
            RuntimeException failure = sendFailures.get(accessToken);
            if (failure != null) {
                throw failure;
            }
            if (sendFailureWhenTextContains != null
                    && message.text().contains(sendFailureWhenTextContains)) {
                throw new KakaoNotificationException("503");
            }
            sent.add(new Sent(accessToken, message));
        }

        List<String> recipientsOf(String text) {
            return sent.stream()
                    .filter(s -> s.message().text().contains(text))
                    .map(Sent::accessToken)
                    .toList();
        }
    }

    private static final AesGcmTokenCipher CIPHER = newCipher();

    private static AesGcmTokenCipher newCipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new AesGcmTokenCipher(Base64.getEncoder().encodeToString(key));
    }

    private FakeChannel channel;
    private FakeCursor cursor;
    private FakeConsents consents;
    private FakeKakao kakao;
    private SlackNoticeForwardService service;

    @BeforeEach
    void setUp() {
        channel = new FakeChannel();
        cursor = new FakeCursor();
        consents = new FakeConsents();
        kakao = new FakeKakao();
        // 퍼머링크 관련 테스트가 이 설정을 쓴다. 퍼머링크의 기본값은 꺼짐이므로 여기서는 명시적으로 켠다.
        service =
                serviceWith(new SlackNoticeSettings(CHANNEL, 3, 24, "https://front.example", true));
        cursor.store.put(CHANNEL, "1700000000.000000");
    }

    private SlackNoticeForwardService serviceWith(SlackNoticeSettings settings) {
        return new SlackNoticeForwardService(
                channel,
                cursor,
                consents,
                kakao,
                CIPHER,
                settings,
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    private void addRecipient(long userId, String name) {
        FakeConsents.Row row = new FakeConsents.Row();
        row.name = name;
        row.encrypted = CIPHER.encrypt("rt-" + userId, "kakao-notify:" + userId);
        consents.rows.put(userId, row);
    }

    private static SlackChannelMessage msg(String ts, String text) {
        return new SlackChannelMessage(ts, "U1", text, null, 0, 0);
    }

    @Test
    @DisplayName("처음 켜면 과거 공지는 보내지 않고 가장 최근 글 시점에서 시작한다")
    void firstRunStartsFromLatest() {
        cursor.store.clear();
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "과거 공지"));

        service.forwardNewNotices();

        assertThat(kakao.sent).isEmpty();
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
    }

    @Test
    @DisplayName("동의 유효 AND 알림 켜짐인 사람에게만 보내고, 본문은 평문으로 바뀌어 링크와 함께 간다")
    void sendsOnlyToConsentedWithAlarmOn() {
        addRecipient(1, "on");
        addRecipient(2, "alarm-off");
        consents.rows.get(2L).alarm = false;
        addRecipient(3, "revoked");
        consents.rows.get(3L).revoked = true;
        addRecipient(4, "deleted");
        consents.rows.get(4L).deleted = true;
        channel.messages.add(msg("1700000100.000100", "<!channel> 내일 <https://a.kr|신청서> 제출"));

        service.forwardNewNotices();

        assertThat(kakao.sent).hasSize(1);
        FakeKakao.Sent sent = kakao.sent.get(0);
        assertThat(sent.accessToken()).isEqualTo("access-of-rt-1");
        assertThat(sent.message().text()).isEqualTo("📢 [공지] @channel 내일 신청서(https://a.kr) 제출");
        assertThat(sent.message().linkUrl()).isEqualTo("https://front.example");
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
    }

    @Test
    @DisplayName("목록을 읽은 뒤 발송 직전에 알림을 껐거나 동의를 해지한 사람에게는 보내지 않는다(발송 시점 재확인)")
    void rechecksRightBeforeSending() {
        addRecipient(1, "stays");
        addRecipient(2, "turns-off");
        channel.messages.add(msg("1700000100.000100", "공지"));
        // 목록 조회가 끝난 직후 2번이 알림을 끈 상황을 만든다.
        FakeConsents racing =
                new FakeConsents() {
                    @Override
                    public List<KakaoRecipient> findActiveRecipients() {
                        List<KakaoRecipient> listed = super.findActiveRecipients();
                        rows.get(2L).alarm = false;
                        return listed;
                    }
                };
        racing.rows.putAll(consents.rows);
        consents = racing;
        service = serviceWith(new SlackNoticeSettings(CHANNEL, 3, 24, "https://front.example"));

        service.forwardNewNotices();

        assertThat(kakao.sent)
                .extracting(FakeKakao.Sent::accessToken)
                .containsExactly("access-of-rt-1");
    }

    @Test
    @DisplayName("이미 보낸 공지는 다시 보내지 않는다(같은 주기를 여러 번 돌려도)")
    void doesNotSendTwice() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "한 번만"));

        service.forwardNewNotices();
        service.forwardNewNotices();
        service.forwardNewNotices();

        assertThat(kakao.sent).hasSize(1);
    }

    @Test
    @DisplayName("공지가 여러 개여도 사람마다 토큰 갱신은 한 번만 하고, 오래된 공지부터 순서대로 보낸다")
    void refreshesOncePerUserPerRun() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "첫째"));
        channel.messages.add(msg("1700000200.000100", "둘째"));

        service.forwardNewNotices();

        assertThat(kakao.refreshCalls).containsExactly("rt-1");
        assertThat(kakao.sent)
                .extracting(s -> s.message().text())
                .containsExactly("📢 [공지] 첫째", "📢 [공지] 둘째");
    }

    @Test
    @DisplayName("수신 대상이 0명이면 예외 없이 경고만 남기고 커서를 옮기지 않아, 대상이 생기면 그때 전달된다")
    void noRecipientsHoldsNotices() {
        channel.messages.add(msg("1700000100.000100", "대상 없을 때 온 공지"));

        service.forwardNewNotices();

        assertThat(kakao.sent).isEmpty();
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000000.000000");

        addRecipient(1, "late");
        service.forwardNewNotices();

        assertThat(kakao.sent).hasSize(1);
        assertThat(kakao.sent.get(0).message().text()).contains("대상 없을 때 온 공지");
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
    }

    @Test
    @DisplayName("대상 조회(DB)가 실패해도 예외로 끝나지 않고 커서를 그대로 둔 채 다음 주기에 재시도한다")
    void recipientLookupFailureHolds() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "공지"));
        consents.failList = true;

        service.forwardNewNotices();

        assertThat(kakao.sent).isEmpty();
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000000.000000");

        consents.failList = false;
        service.forwardNewNotices();
        assertThat(kakao.sent).hasSize(1);
    }

    @Test
    @DisplayName("보류 한도(24시간)를 넘긴 공지는 새 수신자에게 쏟아내지 않고 건너뛰되 커서는 넘어간다")
    void staleNoticesAreNotDumpedOnNewRecipients() {
        long tooOld = NOW - 25 * 3600L;
        channel.messages.add(msg(tooOld + ".000100", "오래된 공지"));
        channel.messages.add(msg((NOW - 60) + ".000100", "방금 공지"));
        addRecipient(1, "late");

        service.forwardNewNotices();

        assertThat(kakao.sent).hasSize(1);
        assertThat(kakao.sent.get(0).message().text()).contains("방금 공지");
        assertThat(cursor.store).containsEntry(CHANNEL, (NOW - 60) + ".000100");
    }

    @Test
    @DisplayName("보낼 새 글이 없거나 시스템 메시지뿐인 주기에는 수신 대상을 조회하지 않는다")
    void doesNotQueryRecipientsWhenNothingToSend() {
        service.forwardNewNotices();
        channel.messages.add(
                new SlackChannelMessage("1700000100.000100", "U1", "joined", "channel_join", 0, 0));
        service.forwardNewNotices();

        assertThat(consents.listCalls).isZero();
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
    }

    @Test
    @DisplayName("refresh_token 이 invalid_grant 면 그 사람의 동의를 해지 처리하고, 다른 사람에게는 계속 보낸다")
    void invalidGrantRevokesThatUserOnly() {
        addRecipient(1, "revoker");
        addRecipient(2, "fine");
        kakao.refreshFailures.put("rt-1", new KakaoInvalidGrantException("invalid_grant"));
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(consents.rows.get(1L).revoked).isTrue();
        assertThat(consents.rows.get(2L).revoked).isFalse();
        assertThat(kakao.sent)
                .extracting(FakeKakao.Sent::accessToken)
                .containsExactly("access-of-rt-2");

        // 해지된 뒤로는 대상에서 빠진다: 다음 공지에서 갱신 시도조차 하지 않는다.
        kakao.refreshCalls.clear();
        channel.messages.add(msg("1700000200.000100", "다음 공지"));
        service.forwardNewNotices();
        assertThat(kakao.refreshCalls).containsExactly("rt-2");
    }

    @Test
    @DisplayName("발송 때 동의 항목 부족(-402)이면 그 동의를 해지 처리한다")
    void insufficientScopeRevokes() {
        addRecipient(1, "no-scope");
        kakao.sendFailures.put("access-of-rt-1", new KakaoInsufficientScopeException("-402"));
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(consents.rows.get(1L).revoked).isTrue();
    }

    @Test
    @DisplayName("카카오가 일시적으로 실패하면(갱신·발송 모두) 해지하지 않고 그 사람만 건너뛴다")
    void transientFailureDoesNotRevoke() {
        addRecipient(1, "refresh-fails");
        addRecipient(2, "send-fails");
        addRecipient(3, "fine");
        kakao.refreshFailures.put("rt-1", new KakaoNotificationException("503"));
        kakao.sendFailures.put("access-of-rt-2", new KakaoNotificationException("timeout"));
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(consents.rows.values()).noneMatch(r -> r.revoked);
        assertThat(kakao.sent)
                .extracting(FakeKakao.Sent::accessToken)
                .containsExactly("access-of-rt-3");
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
    }

    @Test
    @DisplayName("카카오가 새 refresh_token 을 주면 암호화해 저장하고, 다음 실행은 새 토큰으로 갱신한다")
    void rotatedRefreshTokenIsStoredAndUsed() {
        addRecipient(1, "a");
        String before = consents.rows.get(1L).encrypted;
        kakao.rotated.put("rt-1", "rt-1-new");
        channel.messages.add(msg("1700000100.000100", "첫 공지"));

        service.forwardNewNotices();

        String after = consents.rows.get(1L).encrypted;
        assertThat(after).isNotEqualTo(before).doesNotContain("rt-1-new");
        assertThat(CIPHER.decrypt(after, "kakao-notify:1")).isEqualTo("rt-1-new");

        kakao.refreshCalls.clear();
        channel.messages.add(msg("1700000200.000100", "둘째 공지"));
        service.forwardNewNotices();
        assertThat(kakao.refreshCalls).containsExactly("rt-1-new");
    }

    @Test
    @DisplayName("회전된 토큰을 저장하려는 사이 그 사람이 다시 동의해 토큰이 바뀌었다면 새 동의를 덮어쓰지 않는다")
    void rotationDoesNotClobberConcurrentReConsent() {
        addRecipient(1, "a");
        String reConsented = CIPHER.encrypt("rt-1-reconsent", "kakao-notify:1");
        kakao.rotated.put("rt-1", "rt-1-new");
        FakeConsents racing =
                new FakeConsents() {
                    @Override
                    public boolean rotateIfTokenUnchanged(
                            Long userId, String expected, String newToken) {
                        rows.get(userId).encrypted = reConsented; // 회전 저장 직전에 재동의가 끼어듦
                        return super.rotateIfTokenUnchanged(userId, expected, newToken);
                    }
                };
        racing.rows.putAll(consents.rows);
        consents = racing;
        service = serviceWith(new SlackNoticeSettings(CHANNEL, 3, 24, "https://front.example"));
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(consents.rows.get(1L).encrypted).isEqualTo(reConsented);
    }

    @Test
    @DisplayName("해지 처리 직전에 그 사람이 다시 동의해 토큰이 바뀌었다면 새 동의를 해지하지 않는다")
    void revokeDoesNotKillConcurrentReConsent() {
        addRecipient(1, "a");
        String reConsented = CIPHER.encrypt("rt-1-reconsent", "kakao-notify:1");
        kakao.refreshFailures.put("rt-1", new KakaoInvalidGrantException("invalid_grant"));
        FakeConsents racing =
                new FakeConsents() {
                    @Override
                    public boolean revokeIfTokenUnchanged(
                            Long userId, String expected, LocalDateTime now) {
                        rows.get(userId).encrypted = reConsented;
                        return super.revokeIfTokenUnchanged(userId, expected, now);
                    }
                };
        racing.rows.putAll(consents.rows);
        consents = racing;
        service = serviceWith(new SlackNoticeSettings(CHANNEL, 3, 24, "https://front.example"));
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(consents.rows.get(1L).revoked).isFalse();
        assertThat(consents.revokedUsers).isEmpty();
    }

    @Test
    @DisplayName("저장된 토큰을 복호화할 수 없으면(키 분실·변경) 해지하지 않고 건너뛴다 — 다시 동의하면 되살아난다")
    void undecryptableTokenIsSkippedNotRevoked() {
        addRecipient(1, "key-lost");
        consents.rows.get(1L).encrypted = "v1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        addRecipient(2, "fine");
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(consents.rows.get(1L).revoked).isFalse();
        assertThat(kakao.sent)
                .extracting(FakeKakao.Sent::accessToken)
                .containsExactly("access-of-rt-2");
    }

    @Test
    @DisplayName("공지가 한도보다 많이 쌓였으면 최근 공지만 보내고 앞부분은 요약 한 줄로 대신한다")
    void capsBacklogWithSummary() {
        addRecipient(1, "a");
        for (int i = 1; i <= 8; i++) {
            channel.messages.add(msg("17000001%02d.000100".formatted(i), "공지" + i));
        }

        service.forwardNewNotices();

        List<String> texts = kakao.sent.stream().map(s -> s.message().text()).toList();
        assertThat(texts).hasSize(4);
        assertThat(texts.get(0)).contains("이전 5건").contains("생략");
        assertThat(texts.get(1)).contains("공지6");
        assertThat(texts.get(3)).contains("공지8");
    }

    @Test
    @DisplayName("너무 긴 공지는 카카오 한도(200자) 이하로 잘리고, 첨부만 있는 글도 전달된다")
    void longAndAttachmentOnly() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "가".repeat(500)));
        channel.messages.add(
                new SlackChannelMessage("1700000200.000100", "U1", "", "file_share", 0, 2));

        service.forwardNewNotices();

        assertThat(kakao.sent).hasSize(2);
        String longText = kakao.sent.get(0).message().text();
        assertThat(longText.codePointCount(0, longText.length()))
                .isLessThanOrEqualTo(KakaoMessage.MAX_TEXT_LENGTH);
        assertThat(kakao.sent.get(1).message().text()).contains("본문 없음").contains("첨부 2개");
    }

    @Test
    @DisplayName("채널 조회 실패·커서 읽기 실패 시 아무것도 보내지 않고 커서도 그대로 둔다")
    void failuresKeepCursor() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "공지"));
        channel.failFetch = true;

        service.forwardNewNotices();
        assertThat(kakao.sent).isEmpty();
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000000.000000");

        channel.failFetch = false;
        cursor.failRead = true;
        service.forwardNewNotices();
        assertThat(kakao.sent).isEmpty();
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000000.000000");
    }

    @Test
    @DisplayName("토큰이나 인가 코드 같은 값은 발송 대상 객체의 문자열 표현에 나오지 않는다")
    void secretsAreNotInToString() {
        KakaoRecipient recipient = new KakaoRecipient(1L, "a", "v1.secret-ciphertext");
        KakaoGrant grant =
                new KakaoGrant("123", "refresh-secret", java.util.Set.of("talk_message"));
        KakaoRefreshedToken refreshed = new KakaoRefreshedToken("access-secret", "refresh-secret");

        assertThat(recipient.toString()).doesNotContain("secret");
        assertThat(grant.toString()).doesNotContain("secret");
        assertThat(refreshed.toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("공지 카톡은 해당 슬랙 글의 퍼머링크를 열도록 보내고, 퍼머링크는 공지 한 건당 한 번만 조회한다")
    void linksToSlackPermalink() {
        addRecipient(1, "a");
        addRecipient(2, "b");
        channel.messages.add(msg("1700000100.000100", "공지"));
        channel.permalinks.put(
                "1700000100.000100", "https://ws.slack.com/archives/C0NOTICE/p1700000100000100");

        service.forwardNewNotices();

        assertThat(kakao.sent).hasSize(2);
        assertThat(kakao.sent)
                .allSatisfy(
                        s ->
                                assertThat(s.message().linkUrl())
                                        .isEqualTo(
                                                "https://ws.slack.com/archives/C0NOTICE/p1700000100000100"));
        assertThat(channel.permalinkCalls).as("수신자 수와 무관하게 공지당 한 번").hasSize(1);
    }

    @Test
    @DisplayName("공지마다 자기 글의 퍼머링크로 간다")
    void eachNoticeHasItsOwnLink() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "첫째"));
        channel.messages.add(msg("1700000200.000100", "둘째"));
        channel.permalinks.put("1700000100.000100", "https://ws.slack.com/p1");
        channel.permalinks.put("1700000200.000100", "https://ws.slack.com/p2");

        service.forwardNewNotices();

        assertThat(kakao.sent)
                .extracting(s -> s.message().linkUrl())
                .containsExactly("https://ws.slack.com/p1", "https://ws.slack.com/p2");
    }

    @Test
    @DisplayName("퍼머링크를 못 얻으면(빈 값) 기본 링크로 폴백하고 발송은 그대로 된다")
    void fallsBackWhenNoPermalink() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(kakao.sent).hasSize(1);
        assertThat(kakao.sent.get(0).message().linkUrl()).isEqualTo("https://front.example");
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
    }

    @Test
    @DisplayName("퍼머링크 조회가 예외로 실패해도(네트워크·권한 등) 기본 링크로 폴백하며 발송을 막지 않는다")
    void fallsBackWhenPermalinkLookupThrows() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "공지"));

        channel.permalinkFailure = new SlackChannelException("missing_scope");
        service.forwardNewNotices();
        assertThat(kakao.sent).hasSize(1);
        assertThat(kakao.sent.get(0).message().linkUrl()).isEqualTo("https://front.example");

        kakao.sent.clear();
        cursor.store.put(CHANNEL, "1700000000.000000");
        channel.permalinkFailure = new IllegalStateException("boom");
        service.forwardNewNotices();
        assertThat(kakao.sent).hasSize(1);
        assertThat(kakao.sent.get(0).message().linkUrl()).isEqualTo("https://front.example");
    }

    @Test
    @DisplayName("http(s) 주소가 아닌 이상한 값이 오면 쓰지 않고 기본 링크로 폴백한다")
    void ignoresNonHttpPermalink() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "공지"));
        channel.permalinks.put("1700000100.000100", "javascript:alert(1)");

        service.forwardNewNotices();

        assertThat(kakao.sent.get(0).message().linkUrl()).isEqualTo("https://front.example");
    }

    @Test
    @DisplayName("'이전 N건 생략' 요약은 가리킬 글이 없으니 기본 링크를 쓰고, 그 요약을 위해 퍼머링크를 조회하지 않는다")
    void summaryUsesDefaultLink() {
        addRecipient(1, "a");
        for (int i = 1; i <= 5; i++) {
            String ts = "17000001%02d.000100".formatted(i);
            channel.messages.add(msg(ts, "공지" + i));
            channel.permalinks.put(ts, "https://ws.slack.com/p" + i);
        }

        service.forwardNewNotices();

        List<FakeKakao.Sent> sent = kakao.sent;
        assertThat(sent).hasSize(4); // 요약 1 + 최근 3건 (maxPerPoll=3)
        assertThat(sent.get(0).message().text()).contains("생략");
        assertThat(sent.get(0).message().linkUrl()).isEqualTo("https://front.example");
        assertThat(sent.get(1).message().linkUrl()).isEqualTo("https://ws.slack.com/p3");
        assertThat(channel.permalinkCalls).as("보내는 공지 3건만 조회").hasSize(3);
    }

    @Test
    @DisplayName("퍼머링크를 끄면(안전 스위치) 슬랙을 조회하지 않고 항상 기본 링크를 쓴다")
    void permalinkCanBeDisabled() {
        service =
                serviceWith(
                        new SlackNoticeSettings(CHANNEL, 3, 24, "https://front.example", false));
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "공지"));
        channel.permalinks.put("1700000100.000100", "https://ws.slack.com/p1");

        service.forwardNewNotices();

        assertThat(kakao.sent.get(0).message().linkUrl()).isEqualTo("https://front.example");
        assertThat(channel.permalinkCalls).isEmpty();
    }

    @Test
    @DisplayName("수신 대상이 없어 보류되는 주기에는 퍼머링크를 조회하지 않는다(불필요한 슬랙 호출 방지)")
    void noPermalinkLookupWhenNoRecipients() {
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(channel.permalinkCalls).isEmpty();
    }

    @Test
    @DisplayName("퍼머링크는 기본이 꺼짐이다 - 별도 지정이 없으면 슬랙을 조회하지 않고 기본 링크를 쓴다")
    void permalinkIsOffByDefault() {
        SlackNoticeSettings defaults =
                new SlackNoticeSettings(CHANNEL, 3, 24, "https://front.example");
        assertThat(defaults.permalinkEnabled()).isFalse();

        service = serviceWith(defaults);
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "공지"));
        channel.permalinks.put("1700000100.000100", "https://ws.slack.com/p1");

        service.forwardNewNotices();

        assertThat(kakao.sent.get(0).message().linkUrl()).isEqualTo("https://front.example");
        assertThat(channel.permalinkCalls).isEmpty();
    }

    // ---- 커서 전진 규칙(M-11): 아무에게도 못 보냈고 재시도로 풀릴 수 있으면 보류, 그 밖에는 전진 ----

    private static final String START = "1700000000.000000";

    @Test
    @DisplayName("전원이 일시 장애로 실패하면 커서를 옮기지 않고, 복구된 뒤 다음 주기에 한 번씩만 전달한다")
    void allTransientFailuresHoldCursorAndRetry() {
        addRecipient(1, "a");
        addRecipient(2, "b");
        kakao.sendFailures.put("access-of-rt-1", new KakaoNotificationException("503"));
        kakao.sendFailures.put("access-of-rt-2", new KakaoNotificationException("timeout"));
        channel.messages.add(msg("1700000100.000100", "중요 공지"));

        service.forwardNewNotices();

        assertThat(kakao.sent).isEmpty();
        assertThat(cursor.store).containsEntry(CHANNEL, START);
        assertThat(consents.rows.values()).noneMatch(r -> r.revoked);

        kakao.sendFailures.clear(); // 카카오 복구
        service.forwardNewNotices();
        service.forwardNewNotices();

        assertThat(kakao.recipientsOf("중요 공지"))
                .as("복구 뒤 사람마다 정확히 한 번")
                .containsExactlyInAnyOrder("access-of-rt-1", "access-of-rt-2");
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
    }

    @Test
    @DisplayName("토큰 갱신이 전원 실패해도(카카오 5xx 등) 커서를 옮기지 않는다")
    void allRefreshFailuresHoldCursor() {
        addRecipient(1, "a");
        kakao.refreshFailures.put("rt-1", new KakaoNotificationException("503"));
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(cursor.store).containsEntry(CHANNEL, START);
        kakao.refreshFailures.clear();
        service.forwardNewNotices();
        assertThat(kakao.sent).hasSize(1);
    }

    @Test
    @DisplayName("발송을 시도조차 못 한 일시 장애(발송 직전 수신 상태 조회 실패)도 커서를 옮기지 않는다")
    void couldNotEvenAttemptHoldsCursor() {
        addRecipient(1, "a");
        consents.failFind = true;
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(kakao.sent).isEmpty();
        assertThat(cursor.store).containsEntry(CHANNEL, START);

        consents.failFind = false;
        service.forwardNewNotices();
        assertThat(kakao.sent).hasSize(1);
    }

    @Test
    @DisplayName("저장된 토큰을 전원 복호화하지 못하면(암호화 키 문제) 공지를 잃지 않도록 커서를 옮기지 않는다")
    void allUndecryptableHoldsCursor() {
        addRecipient(1, "a");
        consents.rows.get(1L).encrypted = "v1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(cursor.store).containsEntry(CHANNEL, START);
    }

    @Test
    @DisplayName("일부라도 성공하면 커서를 옮긴다 - 실패한 사람에게 다시 보내려 같은 공지를 성공한 사람에게 중복 발송하지 않는다")
    void partialSuccessAdvancesWithoutDuplicates() {
        addRecipient(1, "fails");
        addRecipient(2, "fine");
        kakao.sendFailures.put("access-of-rt-1", new KakaoNotificationException("503"));
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();
        kakao.sendFailures.clear();
        service.forwardNewNotices();

        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
        assertThat(kakao.recipientsOf("공지"))
                .as("성공한 사람에게 두 번 가지 않는다")
                .containsExactly("access-of-rt-2");
    }

    @Test
    @DisplayName("영구 실패(동의 철회·토큰 만료·-402)뿐이면 재시도해도 소용없으니 커서를 옮긴다 - 무한 재시도하지 않는다")
    void onlyPermanentFailuresAdvanceCursor() {
        addRecipient(1, "withdrew");
        addRecipient(2, "expired");
        addRecipient(3, "no-scope");
        kakao.refreshFailures.put("rt-1", new KakaoInvalidGrantException("invalid_grant"));
        kakao.refreshFailures.put("rt-2", new KakaoInvalidGrantException("invalid_grant"));
        kakao.sendFailures.put("access-of-rt-3", new KakaoInsufficientScopeException("-402"));
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();

        assertThat(kakao.sent).isEmpty();
        assertThat(consents.revokedUsers).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
    }

    @Test
    @DisplayName("목록을 읽은 뒤 전원이 동의를 해지하거나 알림을 껐다면(영구) 커서를 옮긴다")
    void allConsentsGoneBetweenListAndSendAdvances() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "공지"));
        // 목록 조회 직후 발송 직전 재확인에서 빠지도록, 조회 시점에만 활성으로 보이게 한다.
        FakeConsents.Row row = consents.rows.get(1L);
        consents.afterListHook = () -> row.alarm = false;

        service.forwardNewNotices();

        assertThat(kakao.sent).isEmpty();
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");
    }

    @Test
    @DisplayName(
            "여러 공지 중 앞 공지는 나갔고 뒤 공지가 전원 일시 실패하면, 커서는 앞 공지까지만 가고 다음 주기에 뒤 공지부터 이어서 보낸다(앞 공지 중복 없음)")
    void resumesFromFailedNoticeWithoutResendingEarlierOnes() {
        addRecipient(1, "a");
        channel.messages.add(msg("1700000100.000100", "첫째"));
        channel.messages.add(msg("1700000200.000100", "둘째"));
        channel.messages.add(msg("1700000300.000100", "셋째"));
        kakao.sendFailureWhenTextContains = "둘째";

        service.forwardNewNotices();

        assertThat(kakao.sent).extracting(s -> s.message().text()).containsExactly("📢 [공지] 첫째");
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000100.000100");

        kakao.sendFailureWhenTextContains = null;
        service.forwardNewNotices();

        assertThat(kakao.sent)
                .extracting(s -> s.message().text())
                .containsExactly("📢 [공지] 첫째", "📢 [공지] 둘째", "📢 [공지] 셋째");
        assertThat(cursor.store).containsEntry(CHANNEL, "1700000300.000100");
    }

    @Test
    @DisplayName("보류 한도(24시간)를 넘기면 계속 실패하던 공지도 더 재시도하지 않고 건너뛴다 - 재시도는 유한하다")
    void retriesAreBoundedByHoldLimit() {
        addRecipient(1, "a");
        kakao.sendFailures.put("access-of-rt-1", new KakaoNotificationException("503"));
        String before = (NOW - 30 * 3600L) + ".000000";
        cursor.store.put(CHANNEL, before);
        channel.messages.add(msg((NOW - 23 * 3600L) + ".000100", "오래 못 나간 공지"));

        service.forwardNewNotices();
        assertThat(cursor.store).containsEntry(CHANNEL, before);

        // 한 시간이 더 지나 보류 한도를 넘었다.
        SlackNoticeForwardService later =
                new SlackNoticeForwardService(
                        channel,
                        cursor,
                        consents,
                        kakao,
                        CIPHER,
                        new SlackNoticeSettings(CHANNEL, 3, 24, "https://front.example", true),
                        Clock.fixed(Instant.ofEpochSecond(NOW + 2 * 3600L), ZoneOffset.UTC));
        later.forwardNewNotices();

        assertThat(cursor.store).containsEntry(CHANNEL, (NOW - 23 * 3600L) + ".000100");
        assertThat(kakao.sent).isEmpty();
    }

    @Test
    @DisplayName("'이전 N건 생략' 요약이 전원 일시 실패하면 개별 공지를 보내지 않고 커서도 그대로 둔다")
    void summaryTransientFailureHolds() {
        addRecipient(1, "a");
        kakao.sendFailureWhenTextContains = "생략";
        for (int i = 1; i <= 8; i++) {
            channel.messages.add(msg("17000001%02d.000100".formatted(i), "공지" + i));
        }

        service.forwardNewNotices();

        assertThat(kakao.sent).isEmpty();
        assertThat(cursor.store).containsEntry(CHANNEL, START);
    }

    @Test
    @DisplayName("요약이 나간 뒤 개별 공지가 실패해도 다음 주기에 요약을 다시 보내지 않는다")
    void summaryIsNotResentAfterLaterHold() {
        addRecipient(1, "a");
        kakao.sendFailureWhenTextContains = "공지6";
        for (int i = 1; i <= 8; i++) {
            channel.messages.add(msg("17000001%02d.000100".formatted(i), "공지" + i));
        }

        service.forwardNewNotices();
        kakao.sendFailureWhenTextContains = null;
        service.forwardNewNotices();

        assertThat(kakao.sent)
                .extracting(s -> s.message().text())
                .filteredOn(t -> t.contains("생략"))
                .hasSize(1);
        assertThat(kakao.sent)
                .extracting(s -> s.message().text())
                .filteredOn(t -> t.contains("공지6"))
                .hasSize(1);
    }

    @Test
    @DisplayName("보류 후 재시도할 때 퍼머링크를 다시 조회하지 않는다 (공지 한 건당 한 번)")
    void retryDoesNotRepeatPermalinkLookup() {
        addRecipient(1, "a");
        kakao.sendFailures.put("access-of-rt-1", new KakaoNotificationException("503"));
        channel.permalinks.put("1700000100.000100", "https://slack.example/p1");
        channel.messages.add(msg("1700000100.000100", "공지"));

        service.forwardNewNotices();
        service.forwardNewNotices();
        service.forwardNewNotices();
        kakao.sendFailures.clear();
        service.forwardNewNotices();

        assertThat(channel.permalinkCalls).hasSize(1);
        assertThat(kakao.sent).hasSize(1);
        assertThat(kakao.sent.get(0).message().linkUrl()).isEqualTo("https://slack.example/p1");
    }
}
