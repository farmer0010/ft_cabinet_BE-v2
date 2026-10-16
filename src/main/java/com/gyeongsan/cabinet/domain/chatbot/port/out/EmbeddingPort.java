package com.gyeongsan.cabinet.domain.chatbot.port.out;

public interface EmbeddingPort {

    float[] embed(String text);

    String modelId();
}
