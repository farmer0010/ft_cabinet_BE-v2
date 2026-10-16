package com.gyeongsan.cabinet.domain.alarm.port.out;

import com.gyeongsan.cabinet.domain.alarm.model.SlackHistory;
import java.util.Optional;

public interface SlackChannelPort {

    SlackHistory fetchNewerThan(String channelId, String oldestTs);

    Optional<String> latestTs(String channelId);

    Optional<String> permalink(String channelId, String ts);
}
