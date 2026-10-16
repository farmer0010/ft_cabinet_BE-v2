package com.gyeongsan.cabinet.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.gyeongsan.cabinet.domain.user.model.FtCursusEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FtCursusParser {

    private FtCursusParser() {}

    public static Optional<List<FtCursusEntry>> fromAttribute(Object cursusUsers) {
        if (!(cursusUsers instanceof List<?> list)) {
            return Optional.empty();
        }
        List<FtCursusEntry> entries = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> cursusUser)) {
                continue;
            }
            if (!(cursusUser.get("cursus") instanceof Map<?, ?> cursus)
                    || !(cursus.get("id") instanceof Number id)) {
                continue;
            }
            Object grade = cursusUser.get("grade");
            entries.add(new FtCursusEntry(id.intValue(), grade instanceof String s ? s : null));
        }
        return Optional.of(entries);
    }

    public static Optional<List<FtCursusEntry>> fromJson(JsonNode cursusUsers) {
        if (cursusUsers == null || !cursusUsers.isArray()) {
            return Optional.empty();
        }
        List<FtCursusEntry> entries = new ArrayList<>();
        for (JsonNode cursusUser : cursusUsers) {
            JsonNode id = cursusUser.path("cursus").path("id");
            if (!id.canConvertToInt() || !id.isNumber()) {
                continue;
            }
            JsonNode grade = cursusUser.path("grade");
            entries.add(
                    new FtCursusEntry(id.intValue(), grade.isTextual() ? grade.asText() : null));
        }
        return Optional.of(entries);
    }
}
