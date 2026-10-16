package com.gyeongsan.cabinet.adapter.out.embedding;

import com.gyeongsan.cabinet.domain.chatbot.port.out.EmbeddingPort;
import java.text.Normalizer;
import java.util.Locale;

public class CharNgramEmbeddingAdapter implements EmbeddingPort {

    static final int DIMENSION = 512;

    @Override
    public String modelId() {
        return "char-ngram-" + DIMENSION;
    }

    @Override
    public float[] embed(String text) {
        String cleaned =
                Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", " ")
                        .strip();
        float[] vector = new float[DIMENSION];
        for (String word : cleaned.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String padded = "^" + word + "$";
            for (int n = 2; n <= 3; n++) {
                for (int i = 0; i + n <= padded.length(); i++) {
                    int hash = padded.substring(i, i + n).hashCode() * 0x9E3779B1;
                    int bucket = Math.floorMod(hash >>> 4, DIMENSION);
                    vector[bucket] += ((hash & 1) == 0) ? 1f : -1f;
                }
            }
        }
        return EmbeddingMath.normalize(vector);
    }
}
