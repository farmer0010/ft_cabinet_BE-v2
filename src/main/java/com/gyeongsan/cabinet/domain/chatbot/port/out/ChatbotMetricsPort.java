package com.gyeongsan.cabinet.domain.chatbot.port.out;

import com.gyeongsan.cabinet.domain.chatbot.model.ChatbotAnswer;
import com.gyeongsan.cabinet.domain.chatbot.model.PersonalIntent;

public interface ChatbotMetricsPort {

    void recordAsk(ChatbotAnswer.Result result);

    default void recordAskDuration(long nanos) {}

    default void recordPersonal(PersonalIntent intent, String outcome) {}
}
