package com.gyeongsan.cabinet.application.kakaonotify;

import com.gyeongsan.cabinet.domain.alarm.model.SlackChannelException;
import com.gyeongsan.cabinet.domain.alarm.model.SlackChannelMessage;
import com.gyeongsan.cabinet.domain.alarm.model.SlackHistory;
import com.gyeongsan.cabinet.domain.alarm.port.out.SlackChannelPort;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoInsufficientScopeException;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoInvalidGrantException;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoMessage;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoNotificationException;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoRecipient;
import com.gyeongsan.cabinet.domain.kakaonotify.model.KakaoRefreshedToken;
import com.gyeongsan.cabinet.domain.kakaonotify.model.NoticeSummaryException;
import com.gyeongsan.cabinet.domain.kakaonotify.model.TokenDecryptionException;
import com.gyeongsan.cabinet.domain.kakaonotify.port.in.ForwardSlackNoticesUseCase;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoConsentRepositoryPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.KakaoNotificationPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.NoticeCursorPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.NoticeSummarizerPort;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.TokenCipherPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class SlackNoticeForwardService implements ForwardSlackNoticesUseCase {

    private static final String PREFIX = "📢 [공지] ";

    private static final int SUMMARY_TARGET_CHARS = 150;

    private final SlackChannelPort channelPort;
    private final NoticeCursorPort cursorPort;
    private final KakaoConsentRepositoryPort consentRepository;
    private final KakaoNotificationPort kakao;
    private final TokenCipherPort cipher;
    private final SlackNoticeSettings settings;
    private final Clock clock;
    private final NoticeSummarizerPort summarizer;

    public SlackNoticeForwardService(
            SlackChannelPort channelPort,
            NoticeCursorPort cursorPort,
            KakaoConsentRepositoryPort consentRepository,
            KakaoNotificationPort kakao,
            TokenCipherPort cipher,
            SlackNoticeSettings settings,
            Clock clock,
            NoticeSummarizerPort summarizer) {
        this.channelPort = channelPort;
        this.cursorPort = cursorPort;
        this.consentRepository = consentRepository;
        this.kakao = kakao;
        this.cipher = cipher;
        this.settings = settings;
        this.clock = clock;
        this.summarizer = summarizer;
    }

    public SlackNoticeForwardService(
            SlackChannelPort channelPort,
            NoticeCursorPort cursorPort,
            KakaoConsentRepositoryPort consentRepository,
            KakaoNotificationPort kakao,
            TokenCipherPort cipher,
            SlackNoticeSettings settings,
            Clock clock) {
        this(
                channelPort,
                cursorPort,
                consentRepository,
                kakao,
                cipher,
                settings,
                clock,
                NoticeSummarizerPort.disabled());
    }

    private enum Failure {
        NONE,
        PERMANENT,
        TRANSIENT
    }

    private static final class Session {
        String accessToken;
        boolean unavailable;
        Failure failure = Failure.NONE;
        String currentEncryptedToken;

        void fail(Failure kind) {
            unavailable = true;
            failure = kind;
        }
    }

    private record SendOutcome(int sent, int transientFailures, int permanentFailures) {
        boolean mustRetry() {
            return sent == 0 && transientFailures > 0;
        }
    }

    private static final int PREPARED_CACHE_SIZE = 20;

    private final Map<String, KakaoMessage> prepared =
            Collections.synchronizedMap(
                    new LinkedHashMap<>() {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, KakaoMessage> e) {
                            return size() > PREPARED_CACHE_SIZE;
                        }
                    });

    @Override
    public void forwardNewNotices() {
        String channelId = settings.channelId();

        Optional<String> cursor;
        try {
            cursor = cursorPort.getCursor(channelId);
        } catch (RuntimeException e) {
            log.warn("[SlackNotice] 커서를 읽지 못해 이번 주기를 건너뜁니다: {}", e.toString());
            return;
        }
        if (cursor.isEmpty()) {
            startFromNow(channelId);
            return;
        }

        SlackHistory history;
        try {
            history = channelPort.fetchNewerThan(channelId, cursor.get());
        } catch (SlackChannelException e) {
            log.warn("[SlackNotice] 채널 조회 실패, 다음 주기에 다시 시도합니다: {}", e.getMessage());
            return;
        }

        List<SlackChannelMessage> all = history.messagesOldestFirst();
        if (all.isEmpty()) {
            return;
        }
        List<SlackChannelMessage> forwardable =
                all.stream().filter(m -> !m.isSystemMessage()).toList();

        List<SlackChannelMessage> fresh = forwardable.stream().filter(m -> !isTooOld(m)).toList();
        int expired = forwardable.size() - fresh.size();
        if (expired > 0) {
            log.warn(
                    "[SlackNotice] 보류 한도({}시간)를 넘긴 공지 {}건은 전달하지 않고 건너뜁니다.",
                    settings.maxHoldHours(),
                    expired);
        }
        if (fresh.isEmpty()) {
            saveCursor(channelId, all.get(all.size() - 1).ts());
            return;
        }

        List<KakaoRecipient> recipients = loadRecipients();
        if (recipients.isEmpty()) {
            return;
        }

        int skipped = Math.max(0, fresh.size() - settings.maxPerPoll());
        List<SlackChannelMessage> toForward = fresh.subList(skipped, fresh.size());

        Map<Long, Session> sessions = new HashMap<>();
        int sent = 0;
        if (skipped > 0 || (history.truncated() && !fresh.isEmpty())) {
            SendOutcome summary =
                    sendToAll(recipients, sessions, summaryMessage(skipped, history.truncated()));
            if (summary.mustRetry()) {
                logHeld("생략 요약", summary, recipients.size());
                return;
            }
            sent += summary.sent();
            if (skipped > 0) {
                saveCursor(channelId, fresh.get(skipped - 1).ts());
            }
        }
        int forwarded = 0;
        for (SlackChannelMessage message : toForward) {
            SendOutcome outcome =
                    sendToAll(recipients, sessions, preparedNoticeMessage(channelId, message));
            if (outcome.mustRetry()) {
                logHeld("공지 ts=" + message.ts(), outcome, recipients.size());
                log.info(
                        "[SlackNotice] 새 글 {}건 확인, {}건 전달 후 보류, 수신자 {}명, 발송 {}건 (생략 {}건)",
                        all.size(),
                        forwarded,
                        recipients.size(),
                        sent,
                        skipped);
                return;
            }
            sent += outcome.sent();
            forwarded++;
            saveCursor(channelId, message.ts());
            prepared.remove(message.ts());
        }
        saveCursor(channelId, all.get(all.size() - 1).ts());
        log.info(
                "[SlackNotice] 새 글 {}건 확인, {}건 전달, 수신자 {}명, 발송 {}건 (생략 {}건)",
                all.size(),
                forwarded,
                recipients.size(),
                sent,
                skipped);
    }

    private void logHeld(String what, SendOutcome outcome, int recipients) {
        log.warn(
                "[SlackNotice] {} 을(를) 아무에게도 보내지 못해(일시 실패 {}명, 영구 실패 {}명, 수신자 {}명) 커서를 옮기지 않고 다음 주기에 다시 시도합니다.",
                what,
                outcome.transientFailures(),
                outcome.permanentFailures(),
                recipients);
    }

    private void startFromNow(String channelId) {
        String start;
        try {
            start = channelPort.latestTs(channelId).orElseGet(this::nowTs);
        } catch (SlackChannelException e) {
            log.warn("[SlackNotice] 시작 지점을 정하지 못했습니다. 다음 주기에 다시 시도합니다: {}", e.getMessage());
            return;
        }
        saveCursor(channelId, start);
        log.info("[SlackNotice] 커서가 없어 과거 공지는 보내지 않고 지금({})부터 시작합니다.", start);
    }

    private String nowTs() {
        Instant now = clock.instant();
        return String.format("%d.%06d", now.getEpochSecond(), now.getNano() / 1000);
    }

    private boolean isTooOld(SlackChannelMessage message) {
        try {
            long seconds = Long.parseLong(message.ts().split("\\.")[0]);
            long ageSeconds = clock.instant().getEpochSecond() - seconds;
            return ageSeconds > settings.maxHoldHours() * 3600L;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void saveCursor(String channelId, String ts) {
        try {
            cursorPort.saveCursor(channelId, ts);
        } catch (RuntimeException e) {
            log.error("[SlackNotice] 커서 저장 실패(다음 주기에 같은 공지가 다시 전달될 수 있음): {}", e.toString());
        }
    }

    private List<KakaoRecipient> loadRecipients() {
        try {
            List<KakaoRecipient> recipients = consentRepository.findActiveRecipients();
            if (recipients.isEmpty()) {
                log.warn(
                        "[SlackNotice] 알림 수신 대상(동의 유효 + 알림 켜짐)이 없어 전달을 보류합니다. 대상이 생기면 다음 주기에 전달됩니다.");
            }
            return recipients;
        } catch (RuntimeException e) {
            log.warn("[SlackNotice] 수신 대상을 조회하지 못해 이번 주기를 건너뜁니다: {}", e.toString());
            return List.of();
        }
    }

    private SendOutcome sendToAll(
            List<KakaoRecipient> recipients, Map<Long, Session> sessions, KakaoMessage message) {
        int sent = 0;
        int transientFailures = 0;
        int permanentFailures = 0;
        for (KakaoRecipient recipient : recipients) {
            Session session = sessions.computeIfAbsent(recipient.userId(), id -> new Session());
            if (!session.unavailable) {
                try {
                    if (session.accessToken != null || openSession(recipient, session)) {
                        kakao.sendToMe(session.accessToken, message);
                        sent++;
                        continue;
                    }
                } catch (KakaoInsufficientScopeException e) {
                    revoke(
                            recipient.userId(),
                            session.currentEncryptedToken,
                            "발송 시 동의 항목 부족(-402)");
                    session.fail(Failure.PERMANENT);
                } catch (RuntimeException e) {
                    log.error(
                            "[SlackNotice] 발송 실패 - userId: {}, 원인: {}",
                            recipient.userId(),
                            e.getClass().getSimpleName());
                    session.fail(Failure.TRANSIENT);
                }
            }
            if (session.failure == Failure.PERMANENT) {
                permanentFailures++;
            } else {
                transientFailures++;
            }
        }
        return new SendOutcome(sent, transientFailures, permanentFailures);
    }

    private boolean openSession(KakaoRecipient listed, Session session) {
        Optional<KakaoRecipient> current;
        try {
            current = consentRepository.findActiveRecipient(listed.userId());
        } catch (RuntimeException e) {
            log.warn("[SlackNotice] 수신 상태를 확인하지 못해 건너뜁니다 - userId: {}", listed.userId());
            session.fail(Failure.TRANSIENT);
            return false;
        }
        if (current.isEmpty()) {
            session.fail(Failure.PERMANENT);
            return false;
        }

        String encrypted = current.get().encryptedRefreshToken();
        session.currentEncryptedToken = encrypted;
        String context = context(listed.userId());

        String refreshToken;
        try {
            refreshToken = cipher.decrypt(encrypted, context);
        } catch (TokenDecryptionException e) {
            log.error(
                    "[SlackNotice] 토큰을 복호화하지 못해 건너뜁니다(암호화 키가 바뀌었거나 값이 손상됨, 유저가 다시 동의해야 함) - userId: {}",
                    listed.userId());
            session.fail(Failure.TRANSIENT);
            return false;
        }

        KakaoRefreshedToken refreshed;
        try {
            refreshed = kakao.refreshAccessToken(refreshToken);
        } catch (KakaoInvalidGrantException e) {
            revoke(listed.userId(), encrypted, "refresh_token invalid_grant(유저가 동의를 해지했거나 토큰 만료)");
            session.fail(Failure.PERMANENT);
            return false;
        } catch (KakaoNotificationException e) {
            log.warn("[SlackNotice] 토큰 갱신 실패, 이번 실행에서는 건너뜁니다 - userId: {}", listed.userId());
            session.fail(Failure.TRANSIENT);
            return false;
        }

        if (refreshed.newRefreshToken() != null
                && !refreshed.newRefreshToken().equals(refreshToken)) {
            rotate(listed.userId(), encrypted, refreshed.newRefreshToken(), session);
        }
        session.accessToken = refreshed.accessToken();
        return true;
    }

    private void rotate(Long userId, String oldEncrypted, String newRefreshToken, Session session) {
        try {
            String newEncrypted = cipher.encrypt(newRefreshToken, context(userId));
            if (consentRepository.rotateIfTokenUnchanged(userId, oldEncrypted, newEncrypted)) {
                session.currentEncryptedToken = newEncrypted;
            } else {
                log.warn(
                        "[SlackNotice] refresh_token 회전을 저장하지 못했습니다(그 사이 값이 바뀜) - userId: {}",
                        userId);
            }
        } catch (RuntimeException e) {
            log.error(
                    "[SlackNotice] 새 refresh_token 저장 실패 - userId: {}, 원인: {}",
                    userId,
                    e.getClass().getSimpleName());
        }
    }

    private void revoke(Long userId, String usedEncryptedToken, String reason) {
        try {
            boolean revoked =
                    consentRepository.revokeIfTokenUnchanged(
                            userId, usedEncryptedToken, LocalDateTime.now(clock));
            log.info(
                    "[SlackNotice] 카카오 동의를 해지 처리했습니다 - userId: {}, 사유: {}{}",
                    userId,
                    reason,
                    revoked ? "" : " (그 사이 값이 바뀌어 변경하지 않음)");
        } catch (RuntimeException e) {
            log.error(
                    "[SlackNotice] 해지 처리 저장 실패 - userId: {}, 원인: {}",
                    userId,
                    e.getClass().getSimpleName());
        }
    }

    static String context(Long userId) {
        return "kakao-notify:" + userId;
    }

    private KakaoMessage preparedNoticeMessage(String channelId, SlackChannelMessage message) {
        KakaoMessage cached = prepared.get(message.ts());
        if (cached != null) {
            return cached;
        }
        KakaoMessage built = noticeMessage(channelId, message);
        prepared.put(message.ts(), built);
        return built;
    }

    private KakaoMessage summaryMessage(int skipped, boolean truncated) {
        String count = truncated ? skipped + "건 이상" : skipped + "건";
        return new KakaoMessage(
                SlackMrkdwn.truncate(
                        PREFIX + "새 공지가 많아 이전 " + count + "은 생략했습니다. 슬랙에서 확인해 주세요.",
                        KakaoMessage.MAX_TEXT_LENGTH),
                settings.linkUrl());
    }

    private KakaoMessage noticeMessage(String channelId, SlackChannelMessage message) {
        String text = SlackMrkdwn.toPlainText(message.text());
        String attachment = "";
        String body = text;
        if (text.isBlank()) {
            body =
                    "(본문 없음"
                            + (message.fileCount() > 0 ? ", 첨부 " + message.fileCount() + "개" : "")
                            + ")";
        } else if (message.fileCount() > 0) {
            attachment = "\n📎 첨부 " + message.fileCount() + "개";
            body = text + attachment;
        }
        String full = PREFIX + body;
        String finalText =
                exceedsLimit(full) ? summarized(message, text, attachment).orElse(null) : null;
        if (finalText == null) {
            finalText = SlackMrkdwn.truncate(full, KakaoMessage.MAX_TEXT_LENGTH);
        }
        return new KakaoMessage(finalText, linkFor(channelId, message));
    }

    private static boolean exceedsLimit(String text) {
        return text.codePointCount(0, text.length()) > KakaoMessage.MAX_TEXT_LENGTH;
    }

    private Optional<String> summarized(
            SlackChannelMessage message, String text, String attachment) {
        int allowed =
                KakaoMessage.MAX_TEXT_LENGTH
                        - PREFIX.codePointCount(0, PREFIX.length())
                        - attachment.codePointCount(0, attachment.length());
        try {
            Optional<String> raw =
                    summarizer.summarize(text, Math.min(SUMMARY_TARGET_CHARS, allowed));
            Optional<String> cleaned = raw.flatMap(r -> NoticeSummarySanitizer.clean(r, allowed));
            if (cleaned.isEmpty()) {
                if (raw.isPresent()) {
                    log.warn(
                            "[SlackNotice] 요약 결과가 비었거나 허용 길이를 넘어 자르기로 대체합니다 - ts: {}",
                            message.ts());
                }
                return Optional.empty();
            }
            return Optional.of(PREFIX + cleaned.get() + attachment);
        } catch (RuntimeException e) {
            log.warn(
                    "[SlackNotice] 공지 요약에 실패해 자르기로 대체합니다 - ts: {}, 원인: {}",
                    message.ts(),
                    failureReason(e));
            return Optional.empty();
        }
    }

    private static final int MAX_REASON_LENGTH = 200;

    static String failureReason(RuntimeException e) {
        String type = e.getClass().getSimpleName();
        if (!(e instanceof NoticeSummaryException) || e.getMessage() == null) {
            return type;
        }
        String detail = e.getMessage().replaceAll("\\s+", " ").strip();
        if (detail.isEmpty()) {
            return type;
        }
        if (detail.codePointCount(0, detail.length()) > MAX_REASON_LENGTH) {
            detail = detail.substring(0, detail.offsetByCodePoints(0, MAX_REASON_LENGTH - 1)) + "…";
        }
        return type + ": " + detail;
    }

    private String linkFor(String channelId, SlackChannelMessage message) {
        if (!settings.permalinkEnabled()) {
            return settings.linkUrl();
        }
        try {
            Optional<String> link = channelPort.permalink(channelId, message.ts());
            if (link.isPresent() && isHttpUrl(link.get())) {
                return link.get();
            }
            log.warn("[SlackNotice] 슬랙 퍼머링크를 얻지 못해 기본 링크를 씁니다 - ts: {}", message.ts());
        } catch (RuntimeException e) {
            log.warn(
                    "[SlackNotice] 슬랙 퍼머링크 조회 중 오류로 기본 링크를 씁니다 - ts: {}, 원인: {}",
                    message.ts(),
                    e.getClass().getSimpleName());
        }
        return settings.linkUrl();
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }
}
