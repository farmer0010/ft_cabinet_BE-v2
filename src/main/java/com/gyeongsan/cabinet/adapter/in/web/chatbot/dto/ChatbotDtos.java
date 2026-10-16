package com.gyeongsan.cabinet.adapter.in.web.chatbot.dto;

import com.gyeongsan.cabinet.domain.chatbot.model.ChatbotAnswer;
import com.gyeongsan.cabinet.domain.chatbot.model.Faq;
import com.gyeongsan.cabinet.domain.chatbot.model.PersonalAnswer;
import com.gyeongsan.cabinet.domain.chatbot.model.PersonalFacts;
import com.gyeongsan.cabinet.domain.chatbot.model.PersonalIntent;
import java.util.List;

public final class ChatbotDtos {

    private ChatbotDtos() {}

    public record AskRequest(String question) {

        @Override
        public String toString() {
            return "AskRequest[question=<" + (question == null ? 0 : question.length()) + "자>]";
        }
    }

    public record FaqView(Long faqId, String category, String question, String answer) {
        public static FaqView of(Faq faq, String question) {
            return new FaqView(faq.id(), faq.category(), question, faq.answer());
        }
    }

    public record SuggestionView(Long faqId, String question) {}

    public record AskResponse(
            String result,
            FaqView answer,
            List<SuggestionView> suggestions,
            String message,
            PersonalActionView personalAction) {

        public AskResponse(
                String result, FaqView answer, List<SuggestionView> suggestions, String message) {
            this(result, answer, suggestions, message, null);
        }

        public static AskResponse from(ChatbotAnswer answer, String fallbackMessage) {
            AskResponse base = baseFrom(answer, fallbackMessage);
            return answer.personalAction() == null
                    ? base
                    : new AskResponse(
                            base.result(),
                            base.answer(),
                            base.suggestions(),
                            base.message(),
                            PersonalActionView.of(answer.personalAction()));
        }

        private static AskResponse baseFrom(ChatbotAnswer answer, String fallbackMessage) {
            return switch (answer.result()) {
                case MATCHED ->
                        new AskResponse(
                                answer.result().name(),
                                FaqView.of(answer.faq(), answer.faq().representativeQuestion()),
                                answer.suggestions().stream()
                                        .map(s -> new SuggestionView(s.faqId(), s.question()))
                                        .toList(),
                                null);
                case SUGGESTED ->
                        new AskResponse(
                                answer.result().name(),
                                null,
                                answer.suggestions().stream()
                                        .map(s -> new SuggestionView(s.faqId(), s.question()))
                                        .toList(),
                                null);
                case UNMATCHED ->
                        new AskResponse(answer.result().name(), null, List.of(), fallbackMessage);
            };
        }
    }

    public record PersonalActionView(String intent, String label) {
        public static PersonalActionView of(PersonalIntent intent) {
            return new PersonalActionView(intent.name(), intent.label());
        }
    }

    public record PersonalRequest(String intent) {}

    public record PersonalResponse(String intent, String message, PersonalFacts facts) {
        public static PersonalResponse of(PersonalAnswer answer) {
            return new PersonalResponse(answer.intent().name(), answer.message(), answer.facts());
        }
    }

    public record PersonalIntentItem(String intent, String label) {}

    public record FaqItem(Long faqId, String question) {}

    public record CategoryGroup(String category, List<FaqItem> faqs) {}

    public record FaqListResponse(List<CategoryGroup> categories) {}
}
