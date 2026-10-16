package com.gyeongsan.cabinet.domain.alarm.model;

import java.util.Set;
import java.util.regex.Pattern;

public record SlackChannelMessage(
        String ts, String user, String text, String subtype, int replyCount, int fileCount) {

    private static final Set<String> SYSTEM_SUBTYPES =
            Set.of(
                    "pinned_item",
                    "unpinned_item",
                    "message_changed",
                    "message_deleted",
                    "tombstone",
                    "reminder_add",
                    "ekm_access_denied",
                    "joiner_notification",
                    "joiner_notification_for_inviter",
                    "bot_add",
                    "bot_remove",
                    "bot_enable",
                    "bot_disable");

    private static final Pattern SYSTEM_PREFIX = Pattern.compile("^(channel_|group_).*");

    public boolean isSystemMessage() {
        if (subtype == null || subtype.isEmpty()) {
            return false;
        }
        return SYSTEM_SUBTYPES.contains(subtype) || SYSTEM_PREFIX.matcher(subtype).matches();
    }
}
