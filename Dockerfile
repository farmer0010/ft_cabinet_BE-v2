FROM amazoncorretto:17 AS chatbot-model
ARG CHATBOT_MODEL=""
WORKDIR /work
COPY chatbot-models.lock ./
COPY scripts/chatbot/fetch-model.sh scripts/chatbot/fetch-model.sh
RUN mkdir -p /model \
    && if [ -n "$CHATBOT_MODEL" ]; then \
         bash scripts/chatbot/fetch-model.sh --mode strict --id "$CHATBOT_MODEL" --out /model; \
       fi

FROM amazoncorretto:17

WORKDIR /app

COPY --from=chatbot-model /model /app/chatbot-model
COPY build/libs/*SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
