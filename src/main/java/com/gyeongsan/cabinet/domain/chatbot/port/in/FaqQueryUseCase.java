package com.gyeongsan.cabinet.domain.chatbot.port.in;

import com.gyeongsan.cabinet.domain.chatbot.model.Faq;
import java.util.List;

public interface FaqQueryUseCase {

    List<Faq> listEnabled();

    Faq getEnabled(long id);
}
