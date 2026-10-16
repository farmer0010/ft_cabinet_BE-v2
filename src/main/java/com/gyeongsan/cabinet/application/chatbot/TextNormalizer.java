package com.gyeongsan.cabinet.application.chatbot;

import java.text.Normalizer;

public final class TextNormalizer {

    private TextNormalizer() {}

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
    }
}
