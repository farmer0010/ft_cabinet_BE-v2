package com.gyeongsan.cabinet.auth.config;

import com.gyeongsan.cabinet.config.FrontendUrls;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AllowedOrigins {

    private AllowedOrigins() {}

    public static List<String> resolve(List<String> configured, String frontendUrl) {
        Set<String> origins = new LinkedHashSet<>();
        if (configured != null) {
            for (String origin : configured) {
                if (origin == null || origin.isBlank()) {
                    continue;
                }
                origins.add(stripTrailingSlash(origin.trim()));
            }
        }
        origins.add(FrontendUrls.originOf(frontendUrl));
        return List.copyOf(origins);
    }

    private static String stripTrailingSlash(String origin) {
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }
}
