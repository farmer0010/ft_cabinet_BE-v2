package com.gyeongsan.cabinet.domain.chatbot.port.out;

import com.gyeongsan.cabinet.domain.chatbot.model.Faq;
import java.util.List;
import java.util.Optional;

public interface FaqRepositoryPort {

    List<Faq> findAll();

    List<Faq> findAllEnabled();

    Optional<Faq> findById(long id);

    Faq save(Faq faq);

    boolean deleteById(long id);

    long count();

    String fingerprint();
}
