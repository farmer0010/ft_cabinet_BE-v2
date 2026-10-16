package com.gyeongsan.cabinet.application.kakaonotify;

import java.util.Optional;
import java.util.regex.Pattern;

final class NoticeSummarySanitizer {

    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern URL =
            Pattern.compile(
                    "(?i)(?:https?://|www\\.)\\S+"
                            + "|\\b(?:[a-z0-9-]+\\.)+(?:com|net|org|io|kr|me|co|app|dev|xyz|link|site|info|biz)\\b(?:/\\S*)?");
    private static final Pattern MD_CHARS = Pattern.compile("[*_`#|]+");

    private static final Pattern STRIKETHROUGH = Pattern.compile("~{2,}");

    private static final Pattern QUOTE = Pattern.compile("(?m)^[ \\t]*>+[ \\t]?");

    private static final Pattern BULLET =
            Pattern.compile("(?m)^\\s*(?:[-•·▶▷◆◇■□●○]|\\d+[.)])\\s+");
    private static final Pattern EMOJI =
            Pattern.compile(
                    "[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{2300}-\\x{23FF}\\x{2B00}-\\x{2BFF}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");
    private static final Pattern LEADING_PREFIX =
            Pattern.compile("^\\s*(?:\\[공지\\]|요약\\s*[:：])\\s*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private NoticeSummarySanitizer() {}

    static Optional<String> clean(String raw, int maxChars) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = MD_LINK.matcher(raw).replaceAll("$1");
        text = URL.matcher(text).replaceAll("");
        text = BULLET.matcher(text).replaceAll("");
        text = QUOTE.matcher(text).replaceAll("");
        text = STRIKETHROUGH.matcher(text).replaceAll("");
        text = MD_CHARS.matcher(text).replaceAll("");
        text = EMOJI.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll("");
        text = WHITESPACE.matcher(text).replaceAll(" ").strip();
        text = LEADING_PREFIX.matcher(text).replaceFirst("").strip();

        if (text.isEmpty() || text.codePointCount(0, text.length()) > maxChars) {
            return Optional.empty();
        }
        return Optional.of(text);
    }
}
