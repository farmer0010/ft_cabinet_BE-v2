package com.gyeongsan.cabinet.domain.chatbot.port.in;

import com.gyeongsan.cabinet.domain.chatbot.model.PersonalAnswer;
import com.gyeongsan.cabinet.domain.chatbot.model.PersonalIntent;

public interface PersonalChatbotUseCase {

    PersonalAnswer answer(Long userId, PersonalIntent intent);
}
