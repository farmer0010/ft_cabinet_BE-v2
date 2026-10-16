package com.gyeongsan.cabinet.config;

import com.gyeongsan.cabinet.domain.user.model.LentTicketRewardPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RewardPolicyConfig {

    @Bean
    public LentTicketRewardPolicy lentTicketRewardPolicy(
            @Value("${app.reward.lent-ticket.default-threshold-minutes:4800}")
                    int defaultThresholdMinutes,
            @Value("${app.reward.lent-ticket.transcender-threshold-minutes:900}")
                    int transcenderThresholdMinutes) {
        return new LentTicketRewardPolicy(defaultThresholdMinutes, transcenderThresholdMinutes);
    }
}
