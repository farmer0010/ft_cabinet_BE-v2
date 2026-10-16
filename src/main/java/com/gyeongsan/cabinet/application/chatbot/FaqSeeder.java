package com.gyeongsan.cabinet.application.chatbot;

import com.gyeongsan.cabinet.domain.chatbot.model.Faq;
import com.gyeongsan.cabinet.domain.chatbot.port.out.FaqRepositoryPort;
import java.util.List;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class FaqSeeder {

    public record SeedItem(
            String seedKey,
            String category,
            String answer,
            Boolean enabled,
            List<String> questions) {}

    private final FaqRepositoryPort faqRepository;

    public FaqSeeder(FaqRepositoryPort faqRepository) {
        this.faqRepository = faqRepository;
    }

    public int seedIfEmpty(List<SeedItem> items) {
        if (items == null || items.isEmpty() || faqRepository.count() > 0) {
            return 0;
        }
        int inserted = 0;
        for (SeedItem item : items) {
            try {
                Faq faq =
                        FaqAdminService.validated(
                                null,
                                item.seedKey(),
                                item.category(),
                                item.answer(),
                                item.enabled() == null || item.enabled(),
                                item.questions(),
                                null);
                faqRepository.save(faq);
                inserted++;
            } catch (RuntimeException e) {
                log.warn("[Chatbot] 초기 FAQ 건너뜀(seedKey={}): {}", item.seedKey(), e.toString());
            }
        }
        log.info("[Chatbot] 초기 FAQ {}건 추가", inserted);
        return inserted;
    }
}
