package com.gyeongsan.cabinet.config;

import com.gyeongsan.cabinet.adapter.out.embedding.CharNgramEmbeddingAdapter;
import com.gyeongsan.cabinet.adapter.out.embedding.LazyEmbeddingPort;
import com.gyeongsan.cabinet.adapter.out.embedding.OnnxEmbeddingAdapter;
import com.gyeongsan.cabinet.adapter.out.metrics.MicrometerChatbotMetricsAdapter;
import com.gyeongsan.cabinet.adapter.out.metrics.ProcessRssMetrics;
import com.gyeongsan.cabinet.application.chatbot.ChatbotService;
import com.gyeongsan.cabinet.application.chatbot.ChatbotSettings;
import com.gyeongsan.cabinet.application.chatbot.FaqAdminService;
import com.gyeongsan.cabinet.application.chatbot.FaqIndexManager;
import com.gyeongsan.cabinet.application.chatbot.FaqSeeder;
import com.gyeongsan.cabinet.application.chatbot.PersonalIntentRouter;
import com.gyeongsan.cabinet.domain.chatbot.port.out.ChatbotMetricsPort;
import com.gyeongsan.cabinet.domain.chatbot.port.out.EmbeddingPort;
import com.gyeongsan.cabinet.domain.chatbot.port.out.FaqRepositoryPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.chatbot.enabled", havingValue = "true")
public class ChatbotConfig {

    @Bean
    public ChatbotSettings chatbotSettings(
            @Value("${app.chatbot.match-threshold:0.93}") double matchThreshold,
            @Value("${app.chatbot.suggest-threshold:0.88}") double suggestThreshold,
            @Value("${app.chatbot.max-suggestions:3}") int maxSuggestions,
            @Value("${app.chatbot.match-margin:0.02}") double matchMargin,
            @Value("${app.chatbot.match-alternatives:2}") int matchAlternatives,
            @Value("${app.chatbot.max-question-length:200}") int maxQuestionLength,
            @Value("${app.chatbot.max-concurrent-embeddings:2}") int maxConcurrentEmbeddings,
            @Value("${app.chatbot.fallback-message}") String fallbackMessage) {
        return new ChatbotSettings(
                matchThreshold,
                suggestThreshold,
                maxSuggestions,
                matchMargin,
                matchAlternatives,
                maxQuestionLength,
                maxConcurrentEmbeddings,
                fallbackMessage);
    }

    @Bean
    public EmbeddingPort chatbotEmbeddingPort(
            @Value("${app.chatbot.embedding.provider:onnx}") String provider,
            @Value("${app.chatbot.embedding.model-id:unknown}") String modelId,
            @Value("${app.chatbot.embedding.model-dir:/app/chatbot-model}") String modelDir,
            @Value("${app.chatbot.embedding.model-file:model.onnx}") String modelFile,
            @Value("${app.chatbot.embedding.tokenizer-file:tokenizer.json}") String tokenizerFile,
            @Value("${app.chatbot.embedding.prefix:}") String prefix,
            @Value("${app.chatbot.embedding.max-tokens:0}") int maxTokens,
            @Value("${app.chatbot.embedding.threads:2}") int threads) {
        return switch (provider) {
            case "onnx" -> {
                Path dir = Path.of(modelDir);
                Properties fromFile = readModelProperties(dir);
                String effectiveId =
                        isUnset(modelId) ? fromFile.getProperty("modelId", "unknown") : modelId;
                String effectivePrefix =
                        prefix.isEmpty() ? fromFile.getProperty("prefix", "") : prefix;
                int effectiveMaxTokens =
                        maxTokens > 0
                                ? maxTokens
                                : parsePositive(fromFile.getProperty("maxTokens"), 128);
                yield new LazyEmbeddingPort(
                        effectiveId,
                        () ->
                                new OnnxEmbeddingAdapter(
                                        effectiveId,
                                        dir.resolve(modelFile),
                                        dir.resolve(tokenizerFile),
                                        effectivePrefix,
                                        effectiveMaxTokens,
                                        threads),
                        Clock.systemUTC(),
                        Duration.ofSeconds(60));
            }
            case "ngram" -> new CharNgramEmbeddingAdapter();
            default ->
                    throw new IllegalArgumentException(
                            "알 수 없는 챗봇 임베딩 방식입니다(onnx 또는 ngram): " + provider);
        };
    }

    @Bean
    public ProcessRssMetrics chatbotProcessRssMetrics() {
        return new ProcessRssMetrics();
    }

    @Bean
    public ChatbotMetricsPort chatbotMetricsPort(MeterRegistry meterRegistry) {
        return new MicrometerChatbotMetricsAdapter(meterRegistry);
    }

    @Bean
    public FaqIndexManager faqIndexManager(
            FaqRepositoryPort faqRepository, EmbeddingPort chatbotEmbeddingPort) {
        return new FaqIndexManager(faqRepository, chatbotEmbeddingPort);
    }

    @Bean
    public ChatbotService chatbotService(
            FaqIndexManager indexManager,
            EmbeddingPort chatbotEmbeddingPort,
            ChatbotMetricsPort metrics,
            ChatbotSettings settings,
            ObjectProvider<PersonalIntentRouter> personalRouter) {
        return new ChatbotService(
                indexManager,
                chatbotEmbeddingPort,
                metrics,
                settings,
                personalRouter.getIfAvailable());
    }

    @Bean
    public FaqAdminService faqAdminService(
            FaqRepositoryPort faqRepository, FaqIndexManager indexManager) {
        return new FaqAdminService(faqRepository, indexManager);
    }

    @Bean
    public FaqSeeder faqSeeder(FaqRepositoryPort faqRepository) {
        return new FaqSeeder(faqRepository);
    }

    private static boolean isUnset(String modelId) {
        return modelId == null || modelId.isBlank() || "unknown".equals(modelId);
    }

    private static int parsePositive(String raw, int fallback) {
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Properties readModelProperties(Path dir) {
        Properties properties = new Properties();
        Path file = dir.resolve("model.properties");
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            return new Properties();
        }
        return properties;
    }
}
