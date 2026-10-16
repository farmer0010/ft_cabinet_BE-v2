package com.gyeongsan.cabinet.adapter.in.web.chatbot;

import com.gyeongsan.cabinet.adapter.in.web.chatbot.dto.ChatbotDtos.AskRequest;
import com.gyeongsan.cabinet.adapter.in.web.chatbot.dto.ChatbotDtos.AskResponse;
import com.gyeongsan.cabinet.adapter.in.web.chatbot.dto.ChatbotDtos.CategoryGroup;
import com.gyeongsan.cabinet.adapter.in.web.chatbot.dto.ChatbotDtos.FaqItem;
import com.gyeongsan.cabinet.adapter.in.web.chatbot.dto.ChatbotDtos.FaqListResponse;
import com.gyeongsan.cabinet.adapter.in.web.chatbot.dto.ChatbotDtos.FaqView;
import com.gyeongsan.cabinet.application.chatbot.ChatbotSettings;
import com.gyeongsan.cabinet.common.ApiResponse;
import com.gyeongsan.cabinet.domain.chatbot.model.Faq;
import com.gyeongsan.cabinet.domain.chatbot.port.in.AskChatbotUseCase;
import com.gyeongsan.cabinet.domain.chatbot.port.in.FaqQueryUseCase;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v4/chatbot")
@RateLimiter(name = "chatbotApi")
@ConditionalOnProperty(name = "app.chatbot.enabled", havingValue = "true")
public class ChatbotController {

    private final AskChatbotUseCase askChatbot;
    private final FaqQueryUseCase faqQuery;
    private final ChatbotSettings settings;

    @PostMapping("/ask")
    public ApiResponse<AskResponse> ask(@RequestBody AskRequest request) {
        return ApiResponse.success(
                AskResponse.from(askChatbot.ask(request.question()), settings.fallbackMessage()));
    }

    @GetMapping("/faqs")
    public ApiResponse<FaqListResponse> faqs() {
        Map<String, List<FaqItem>> grouped = new LinkedHashMap<>();
        for (Faq faq : faqQuery.listEnabled()) {
            grouped.computeIfAbsent(faq.category(), k -> new java.util.ArrayList<>())
                    .add(new FaqItem(faq.id(), faq.representativeQuestion()));
        }
        List<CategoryGroup> groups =
                grouped.entrySet().stream()
                        .map(e -> new CategoryGroup(e.getKey(), e.getValue()))
                        .toList();
        return ApiResponse.success(new FaqListResponse(groups));
    }

    @GetMapping("/faqs/{id}")
    public ApiResponse<FaqView> faq(@PathVariable long id) {
        Faq faq = faqQuery.getEnabled(id);
        return ApiResponse.success(FaqView.of(faq, faq.representativeQuestion()));
    }
}
