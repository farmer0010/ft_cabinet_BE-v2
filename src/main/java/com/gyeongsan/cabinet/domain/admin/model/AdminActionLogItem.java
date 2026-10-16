package com.gyeongsan.cabinet.domain.admin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AdminActionLogItem(
        AdminActionTargetType targetType,
        Long targetId,
        String targetLabel,
        Map<String, Object> before,
        Map<String, Object> after) {

    public AdminActionLogItem {
        if (targetType == null || targetId == null) {
            throw new IllegalArgumentException("대상 종류와 ID 는 필수입니다.");
        }
        before = before == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after = after == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(after));
    }
}
