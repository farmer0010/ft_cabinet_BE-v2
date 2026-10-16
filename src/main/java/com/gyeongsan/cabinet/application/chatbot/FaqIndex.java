package com.gyeongsan.cabinet.application.chatbot;

import com.gyeongsan.cabinet.domain.chatbot.model.Faq;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FaqIndex {

    public static final FaqIndex EMPTY = new FaqIndex(List.of(), Map.of());

    public record Entry(long faqId, String question, float[] vector) {}

    public record Hit(Faq faq, String matchedQuestion, double score) {}

    private final List<Entry> entries;
    private final Map<Long, Faq> faqById;

    public FaqIndex(List<Entry> entries, Map<Long, Faq> faqById) {
        this.entries = List.copyOf(entries);
        this.faqById = Map.copyOf(faqById);
    }

    public int size() {
        return entries.size();
    }

    public List<Hit> search(float[] query, int limit) {
        Map<Long, Hit> best = new HashMap<>();
        for (Entry entry : entries) {
            double score = dot(query, entry.vector());
            Hit current = best.get(entry.faqId());
            if (current == null || score > current.score()) {
                best.put(
                        entry.faqId(),
                        new Hit(faqById.get(entry.faqId()), entry.question(), score));
            }
        }
        List<Hit> hits = new ArrayList<>(best.values());
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalStateException(
                    "벡터 차원이 다릅니다("
                            + a.length
                            + " != "
                            + b.length
                            + "). 모델이 바뀌었다면 색인을 다시 만들어야 합니다.");
        }
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
