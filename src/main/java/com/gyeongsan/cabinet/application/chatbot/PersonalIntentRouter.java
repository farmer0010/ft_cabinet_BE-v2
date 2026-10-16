package com.gyeongsan.cabinet.application.chatbot;

import com.gyeongsan.cabinet.domain.chatbot.model.EmbeddingUnavailableException;
import com.gyeongsan.cabinet.domain.chatbot.model.PersonalIntent;
import com.gyeongsan.cabinet.domain.chatbot.port.out.EmbeddingPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class PersonalIntentRouter {

    private record Entry(PersonalIntent intent, float[] vector) {}

    public record Hit(PersonalIntent intent, double score) {}

    private final Map<PersonalIntent, List<String>> catalog;
    private final EmbeddingPort embedding;
    private volatile List<Entry> entries;

    public PersonalIntentRouter(
            Map<PersonalIntent, List<String>> catalog, EmbeddingPort embedding) {
        this.catalog = Map.copyOf(catalog);
        this.embedding = embedding;
    }

    public Optional<Hit> route(float[] query) {
        List<Entry> built = ensureBuilt();
        if (built.isEmpty()) {
            return Optional.empty();
        }
        PersonalIntent best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Entry entry : built) {
            double score = dot(query, entry.vector());
            if (score > bestScore) {
                bestScore = score;
                best = entry.intent();
            }
        }
        return Optional.of(new Hit(best, bestScore));
    }

    private List<Entry> ensureBuilt() {
        List<Entry> current = entries;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (entries != null) {
                return entries;
            }
            try {
                List<Entry> built = new ArrayList<>();
                for (Map.Entry<PersonalIntent, List<String>> e : catalog.entrySet()) {
                    for (String question : e.getValue()) {
                        String normalized = TextNormalizer.normalize(question);
                        if (!normalized.isEmpty()) {
                            built.add(new Entry(e.getKey(), embedding.embed(normalized)));
                        }
                    }
                }
                entries = List.copyOf(built);
                log.info("[Chatbot] 개인화 인텐트 색인 완료: 질문 표현 {}개", built.size());
                return entries;
            } catch (EmbeddingUnavailableException e) {
                return List.of();
            }
        }
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalStateException("벡터 차원이 다릅니다(" + a.length + " != " + b.length + ").");
        }
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
