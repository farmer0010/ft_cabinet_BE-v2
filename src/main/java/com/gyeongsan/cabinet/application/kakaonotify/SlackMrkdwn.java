package com.gyeongsan.cabinet.application.kakaonotify;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SlackMrkdwn {

    private static final Pattern ANGLE = Pattern.compile("<([^<>]+)>");

    private SlackMrkdwn() {}

    static String toPlainText(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher m = ANGLE.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(convert(m.group(1))));
        }
        m.appendTail(sb);

        String text =
                sb.toString()
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&amp;", "&")
                        .replace("\r\n", "\n");
        return text.replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String convert(String inner) {
        String target = inner;
        String label = null;
        int bar = inner.indexOf('|');
        if (bar >= 0) {
            target = inner.substring(0, bar);
            label = inner.substring(bar + 1);
        }

        if (target.startsWith("!")) {
            String name = target.substring(1);
            if (name.equals("channel") || name.equals("here") || name.equals("everyone")) {
                return "@" + name;
            }
            if (name.startsWith("subteam^")) {
                return hasText(label) ? label : "@그룹";
            }
            return hasText(label) ? label : "";
        }
        if (target.startsWith("@")) {
            return hasText(label) ? "@" + label.replaceFirst("^@", "") : "@멤버";
        }
        if (target.startsWith("#")) {
            return hasText(label) ? "#" + label : "#채널";
        }
        if (target.startsWith("mailto:")) {
            return hasText(label) ? label : target.substring("mailto:".length());
        }
        if (hasText(label) && !label.equals(target)) {
            return label + "(" + target + ")";
        }
        return target;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    static String truncate(String text, int maxLength) {
        if (text.codePointCount(0, text.length()) <= maxLength) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxLength - 1);
        return text.substring(0, end) + "…";
    }
}
