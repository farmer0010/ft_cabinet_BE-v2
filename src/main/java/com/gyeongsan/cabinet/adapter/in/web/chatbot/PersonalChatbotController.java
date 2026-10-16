package com.gyeongsan.cabinet.adapter.in.web.chatbot;

import com.gyeongsan.cabinet.adapter.in.web.chatbot.dto.ChatbotDtos.PersonalIntentItem;
import com.gyeongsan.cabinet.adapter.in.web.chatbot.dto.ChatbotDtos.PersonalRequest;
import com.gyeongsan.cabinet.adapter.in.web.chatbot.dto.ChatbotDtos.PersonalResponse;
import com.gyeongsan.cabinet.auth.domain.UserPrincipal;
import com.gyeongsan.cabinet.common.ApiResponse;
import com.gyeongsan.cabinet.domain.chatbot.model.PersonalIntent;
import com.gyeongsan.cabinet.domain.chatbot.port.in.PersonalChatbotUseCase;
import com.gyeongsan.cabinet.global.exception.ErrorCode;
import com.gyeongsan.cabinet.global.exception.ServiceException;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v4/chatbot/personal")
@RateLimiter(name = "chatbotApi")
@ConditionalOnProperty(
        name = {"app.chatbot.enabled", "app.chatbot.personal.enabled"},
        havingValue = "true")
public class PersonalChatbotController {

    private final PersonalChatbotUseCase personalChatbot;

    @PostMapping
    public ResponseEntity<ApiResponse<PersonalResponse>> answer(
            @RequestBody PersonalRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.getUserId() == null) {
            throw new ServiceException(ErrorCode.USER_NOT_FOUND);
        }
        PersonalIntent intent =
                PersonalIntent.fromName(request == null ? null : request.intent())
                        .orElseThrow(() -> new ServiceException(ErrorCode.CHATBOT_INVALID_INTENT));
        PersonalResponse body =
                PersonalResponse.of(personalChatbot.answer(principal.getUserId(), intent));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(body));
    }

    @GetMapping("/intents")
    public ApiResponse<List<PersonalIntentItem>> intents() {
        return ApiResponse.success(
                Arrays.stream(PersonalIntent.values())
                        .map(i -> new PersonalIntentItem(i.name(), i.label()))
                        .toList());
    }
}
