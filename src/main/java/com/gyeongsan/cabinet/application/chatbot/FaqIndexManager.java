package com.gyeongsan.cabinet.application.chatbot;

import com.gyeongsan.cabinet.domain.chatbot.model.EmbeddingUnavailableException;
import com.gyeongsan.cabinet.domain.chatbot.model.Faq;
import com.gyeongsan.cabinet.domain.chatbot.port.out.EmbeddingPort;
import com.gyeongsan.cabinet.domain.chatbot.port.out.FaqRepositoryPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class FaqIndexManager {

    private static final String WARMUP_TEXT = "준비 확인";

    private final FaqRepositoryPort faqRepository;
    private final EmbeddingPort embedding;
    private final ReentrantLock buildLock = new ReentrantLock();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "faq-index-builder");
                        t.setDaemon(true);
                        return t;
                    });

    private volatile FaqIndex index = FaqIndex.EMPTY;
    private volatile String builtFingerprint;
    private volatile boolean ready;
    private volatile boolean failureLogged;
    private final AtomicBoolean queued = new AtomicBoolean(false);

    public FaqIndexManager(FaqRepositoryPort faqRepository, EmbeddingPort embedding) {
        this.faqRepository = faqRepository;
        this.embedding = embedding;
    }

    public boolean isReady() {
        return ready;
    }

    public FaqIndex current() {
        return index;
    }

    public void requestRebuild() {
        if (queued.compareAndSet(false, true)) {
            executor.submit(
                    () -> {
                        queued.set(false);
                        rebuildSafely();
                    });
        }
    }

    public void refreshIfChanged() {
        try {
            if (!ready || !faqRepository.fingerprint().equals(builtFingerprint)) {
                requestRebuild();
            }
        } catch (RuntimeException e) {
            log.warn("[Chatbot] FAQ 변경 확인 실패: {}", e.toString());
        }
    }

    private void rebuildSafely() {
        try {
            rebuild();
        } catch (EmbeddingUnavailableException e) {
            if (!failureLogged) {
                log.error("[Chatbot] 임베딩 모델을 쓸 수 없어 챗봇 색인을 만들지 못했습니다(계속 재시도): {}", e.getMessage());
                failureLogged = true;
            }
        } catch (RuntimeException e) {
            log.error("[Chatbot] 색인 생성 중 오류: {}", e.toString(), e);
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public void rebuild() {
        buildLock.lock();
        try {
            String fingerprint = faqRepository.fingerprint();

            embedding.embed(WARMUP_TEXT);

            List<Faq> faqs = faqRepository.findAllEnabled();
            List<FaqIndex.Entry> entries = new ArrayList<>();
            Map<Long, Faq> byId = new HashMap<>();
            long started = System.nanoTime();
            for (Faq faq : faqs) {
                byId.put(faq.id(), faq);
                for (String question : faq.questions()) {
                    String normalized = TextNormalizer.normalize(question);
                    if (!normalized.isEmpty()) {
                        entries.add(
                                new FaqIndex.Entry(
                                        faq.id(), question, embedding.embed(normalized)));
                    }
                }
            }

            this.index = new FaqIndex(entries, byId);
            this.builtFingerprint = fingerprint;
            this.ready = true;
            this.failureLogged = false;
            log.info(
                    "[Chatbot] 색인 완료: FAQ {}건, 질문 표현 {}개, 모델 {}, {}ms",
                    faqs.size(),
                    entries.size(),
                    embedding.modelId(),
                    (System.nanoTime() - started) / 1_000_000);
        } finally {
            buildLock.unlock();
        }
    }
}
