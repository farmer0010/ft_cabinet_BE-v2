package com.gyeongsan.cabinet.application.chatbot;

import com.gyeongsan.cabinet.domain.chatbot.model.ChatbotAnswer;
import com.gyeongsan.cabinet.domain.chatbot.model.EmbeddingUnavailableException;
import com.gyeongsan.cabinet.domain.chatbot.model.PersonalIntent;
import com.gyeongsan.cabinet.domain.chatbot.port.in.AskChatbotUseCase;
import com.gyeongsan.cabinet.domain.chatbot.port.out.ChatbotMetricsPort;
import com.gyeongsan.cabinet.domain.chatbot.port.out.EmbeddingPort;
import com.gyeongsan.cabinet.global.exception.ErrorCode;
import com.gyeongsan.cabinet.global.exception.ServiceException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class ChatbotService implements AskChatbotUseCase {

    private static final long GATE_WAIT_MILLIS = 2_000;

    private final FaqIndexManager indexManager;
    private final EmbeddingPort embedding;
    private final ChatbotMetricsPort metrics;
    private final ChatbotSettings settings;
    private final Semaphore embeddingGate;
    private final PersonalIntentRouter personalRouter;

    public ChatbotService(
            FaqIndexManager indexManager,
            EmbeddingPort embedding,
            ChatbotMetricsPort metrics,
            ChatbotSettings settings) {
        this(indexManager, embedding, metrics, settings, null);
    }

    public ChatbotService(
            FaqIndexManager indexManager,
            EmbeddingPort embedding,
            ChatbotMetricsPort metrics,
            ChatbotSettings settings,
            PersonalIntentRouter personalRouter) {
        this.personalRouter = personalRouter;
        this.indexManager = indexManager;
        this.embedding = embedding;
        this.metrics = metrics;
        this.settings = settings;
        this.embeddingGate = new Semaphore(settings.maxConcurrentEmbeddings());
    }

    @Override
    public ChatbotAnswer ask(String rawQuestion) {
        String question = TextNormalizer.normalize(rawQuestion);
        if (question.isEmpty() || question.length() > settings.maxQuestionLength()) {
            throw new ServiceException(ErrorCode.CHATBOT_INVALID_QUESTION);
        }
        if (!indexManager.isReady()) {
            throw new ServiceException(ErrorCode.CHATBOT_NOT_READY);
        }

        long started = System.nanoTime();
        float[] vector = embedQuestion(question);
        int limit =
                Math.max(Math.max(settings.maxSuggestions(), settings.matchAlternatives() + 1), 2);
        List<FaqIndex.Hit> hits = indexManager.current().search(vector, limit);

        ChatbotAnswer answer = decide(hits);
        PersonalIntent action = personalAction(vector, hits);
        if (action != null) {
            answer = answer.withPersonalAction(action);
            metrics.recordPersonal(action, "chip_shown");
        }
        metrics.recordAsk(answer.result());
        metrics.recordAskDuration(System.nanoTime() - started);
        log.debug("[Chatbot] 결과={}, 최고 유사도={}", answer.result(), answer.score());
        return answer;
    }

    private PersonalIntent personalAction(float[] vector, List<FaqIndex.Hit> hits) {
        if (personalRouter == null) {
            return null;
        }
        double bestFaq = hits.isEmpty() ? 0 : hits.get(0).score();
        return personalRouter
                .route(vector)
                .filter(h -> h.score() >= settings.suggestThreshold() && h.score() >= bestFaq)
                .map(PersonalIntentRouter.Hit::intent)
                .orElse(null);
    }

    private ChatbotAnswer decide(List<FaqIndex.Hit> hits) {
        if (hits.isEmpty()) {
            return ChatbotAnswer.unmatched(0);
        }
        FaqIndex.Hit best = hits.get(0);
        double second = hits.size() > 1 ? hits.get(1).score() : 0;
        if (best.score() >= settings.matchThreshold()
                && best.score() - second >= settings.matchMargin()) {
            return new ChatbotAnswer(
                    ChatbotAnswer.Result.MATCHED,
                    best.faq(),
                    best.matchedQuestion(),
                    best.score(),
                    alternatives(hits));
        }
        if (best.score() >= settings.suggestThreshold() && settings.maxSuggestions() > 0) {
            List<ChatbotAnswer.Suggestion> suggestions =
                    hits.stream()
                            .filter(h -> h.score() >= settings.suggestThreshold())
                            .limit(settings.maxSuggestions())
                            .map(
                                    h ->
                                            new ChatbotAnswer.Suggestion(
                                                    h.faq().id(),
                                                    h.faq().representativeQuestion(),
                                                    h.score()))
                            .toList();
            return new ChatbotAnswer(
                    ChatbotAnswer.Result.SUGGESTED,
                    null,
                    best.matchedQuestion(),
                    best.score(),
                    suggestions);
        }
        return ChatbotAnswer.unmatched(best.score());
    }

    private List<ChatbotAnswer.Suggestion> alternatives(List<FaqIndex.Hit> hits) {
        return hits.stream()
                .skip(1)
                .filter(h -> h.score() >= settings.suggestThreshold())
                .limit(settings.matchAlternatives())
                .map(
                        h ->
                                new ChatbotAnswer.Suggestion(
                                        h.faq().id(), h.faq().representativeQuestion(), h.score()))
                .toList();
    }

    private float[] embedQuestion(String question) {
        boolean acquired = false;
        try {
            acquired = embeddingGate.tryAcquire(GATE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ServiceException(ErrorCode.CHATBOT_NOT_READY);
            }
            return embedding.embed(question);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(ErrorCode.CHATBOT_NOT_READY);
        } catch (EmbeddingUnavailableException e) {
            throw new ServiceException(ErrorCode.CHATBOT_NOT_READY);
        } finally {
            if (acquired) {
                embeddingGate.release();
            }
        }
    }
}
