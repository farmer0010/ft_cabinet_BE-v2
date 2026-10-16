package com.gyeongsan.cabinet.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public final class FtFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FtFixtures() {}

    public static final String TRANSCENDER = "cursus-users-transcender.json";
    public static final String CADET = "cursus-users-cadet.json";

    public static String raw(String name) {
        try (InputStream in = FtFixtures.class.getResourceAsStream("/fixtures/ft/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("픽스처가 없습니다: " + name);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> asAttributes(String name) {
        try {
            return MAPPER.readValue(raw(name), new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode asJson(String name) {
        try {
            return MAPPER.readTree(raw(name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
