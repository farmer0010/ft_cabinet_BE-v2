package com.gyeongsan.cabinet.adapter.out.external.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongsan.cabinet.domain.kakaonotify.model.NoticeSummaryException;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.NoticeSummarizerPort;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Log4j2
public class GeminiNoticeSummarizerAdapter implements NoticeSummarizerPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final int MAX_OUTPUT_TOKENS = 1024;

    private final WebClient webClient;
    private final String model;
    private final String apiKey;
    private final String thinkingLevel;
    private final Duration timeout;
    private final CircuitBreaker circuitBreaker;

    public GeminiNoticeSummarizerAdapter(
            WebClient webClient,
            String model,
            String apiKey,
            String thinkingLevel,
            Duration timeout,
            CircuitBreaker circuitBreaker) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Gemini API 키가 비어 있습니다.");
        }
        if (model == null || !MODEL_NAME.matcher(model).matches()) {
            throw new IllegalArgumentException("Gemini 모델 이름 형식이 올바르지 않습니다.");
        }
        this.webClient = webClient;
        this.model = model;
        this.apiKey = apiKey.strip();
        this.thinkingLevel = thinkingLevel == null ? "" : thinkingLevel.strip();
        this.timeout = timeout;
        this.circuitBreaker = circuitBreaker;
    }

    public static WebClient createWebClient(String baseUrl, Duration timeout) {
        HttpClient httpClient =
                HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis())
                        .responseTimeout(timeout);
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public Optional<String> summarize(String text, int maxChars) {
        try {
            return circuitBreaker.executeSupplier(() -> call(text, maxChars));
        } catch (CallNotPermittedException e) {
            throw new NoticeSummaryException("요약 서킷 브레이커가 열려 있어 호출하지 않았습니다.");
        }
    }

    @Override
    public String toString() {
        return "GeminiNoticeSummarizerAdapter[model=" + model + "]";
    }

    private Optional<String> call(String text, int maxChars) {
        Map<String, Object> generationConfig = new java.util.LinkedHashMap<>();
        generationConfig.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        if (!thinkingLevel.isEmpty()) {
            generationConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel));
        }
        Map<String, Object> body =
                Map.of(
                        "systemInstruction",
                        Map.of(
                                "parts",
                                List.of(
                                        Map.of(
                                                "text",
                                                GeminiNoticePrompt.systemInstruction(maxChars)))),
                        "contents",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "parts",
                                        List.of(
                                                Map.of(
                                                        "text",
                                                        GeminiNoticePrompt.userContent(text))))),
                        "generationConfig",
                        generationConfig);

        String response;
        try {
            response =
                    webClient
                            .post()
                            .uri("/v1beta/models/{model}:generateContent", model)
                            .header("x-goog-api-key", apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .exchangeToMono(
                                    r -> {
                                        if (!r.statusCode().is2xxSuccessful()) {
                                            return r.releaseBody()
                                                    .then(
                                                            reactor.core.publisher.Mono.error(
                                                                    new NoticeSummaryException(
                                                                            "Gemini 응답 상태 "
                                                                                    + r.statusCode()
                                                                                            .value())));
                                        }
                                        return r.bodyToMono(String.class);
                                    })
                            .block(timeout.plusSeconds(2));
        } catch (NoticeSummaryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NoticeSummaryException("Gemini 호출에 실패했습니다: " + e.getClass().getSimpleName());
        }
        return parse(response);
    }

    private Optional<String> parse(String response) {
        if (response == null || response.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(response);
        } catch (Exception e) {
            throw new NoticeSummaryException("Gemini 응답을 해석하지 못했습니다.");
        }
        if (root.path("promptFeedback").hasNonNull("blockReason")) {
            log.warn("[NoticeSummary] 입력이 차단되어 요약이 없습니다.");
            return Optional.empty();
        }
        JsonNode candidate = root.path("candidates").path(0);
        String finish = candidate.path("finishReason").asText("");
        if (!finish.isEmpty() && !"STOP".equals(finish)) {
            log.warn("[NoticeSummary] 정상 종료가 아니라 요약을 쓰지 않습니다 - finishReason: {}", finish);
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            sb.append(part.path("text").asText(""));
        }
        String result = sb.toString().strip();
        if (result.isEmpty()) {
            log.warn("[NoticeSummary] 응답에 요약문이 없습니다.");
            return Optional.empty();
        }
        return Optional.of(result);
    }
}
