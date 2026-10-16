package com.gyeongsan.cabinet.domain.alarm.model;

import java.util.List;

public record SlackHistory(List<SlackChannelMessage> messagesOldestFirst, boolean truncated) {}
