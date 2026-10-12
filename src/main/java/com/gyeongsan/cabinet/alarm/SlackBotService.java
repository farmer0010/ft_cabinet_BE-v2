package com.gyeongsan.cabinet.alarm;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Log4j2
public class SlackBotService {

    private final String botToken;
    private final String apiBaseUrl;
    private final RestTemplate restTemplate;
    private final Map<String, String> slackUserIdMap = new ConcurrentHashMap<>();

    /**
     * 연결/읽기 타임아웃을 반드시 둔다. 타임아웃이 없는 {@code new RestTemplate()} 은 Slack 응답이 멈추면 호출 스레드를 무기한 붙잡았고,
     * 스케줄러 스레드에서 호출되면 모든 {@code @Scheduled} 작업이 함께 멈췄다(감사 H-9). 0 이하는 JDK 에서 "무제한"을 뜻하므로 허용하지 않는다.
     */
    public SlackBotService(
            @Value("${slack.token}") String botToken,
            @Value("${slack.api-base-url:https://slack.com/api}") String apiBaseUrl,
            @Value("${slack.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${slack.http.read-timeout-ms:5000}") int readTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "Slack HTTP 타임아웃(slack.http.connect-timeout-ms, slack.http.read-timeout-ms)은 1 이상이어야 합니다."
                            + " 0 이하는 무제한이라 허용하지 않습니다.");
        }
        this.botToken = botToken;
        this.apiBaseUrl =
                apiBaseUrl.endsWith("/")
                        ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                        : apiBaseUrl;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("slack_users.csv");
            if (resource.exists()) {
                try (BufferedReader br =
                        new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
                    String line;
                    boolean isFirstLine = true;
                    while ((line = br.readLine()) != null) {
                        if (isFirstLine) {
                            isFirstLine = false;
                            continue;
                        }
                        String[] parts = line.split(",");
                        if (parts.length >= 2) {
                            String login = parts[0].trim();
                            String slackId = parts[1].trim();
                            slackUserIdMap.put(login, slackId);
                        }
                    }
                }
                log.info("✅ 내부 CSV 파일 내 슬랙 유저 매핑 완료: {}명", slackUserIdMap.size());
            } else {
                log.warn("⚠️ 'slack_users.csv' 파일이 없어 기존 API 탐색 방식으로 구동합니다.");
            }
        } catch (Exception e) {
            log.error("❌ 슬랙 유저 매핑 파일 읽기 실패: {}", e.getMessage());
        }
    }

    public void sendDm(String intraId, String messageContent) {
        try {
            String slackUserId = findSlackIdByIntraId(intraId);

            if (slackUserId == null) {
                log.error("❌ 해당 Intra ID({})를 가진 슬랙 유저를 찾을 수 없습니다.", intraId);
                return;
            }

            sendMessage(slackUserId, messageContent);

        } catch (Exception e) {
            log.error("❌ 슬랙 DM 전송 중 오류 발생: {}", e.getMessage());
        }
    }

    private String findSlackIdByIntraId(String intraId) {
        if (slackUserIdMap.containsKey(intraId)) {
            return slackUserIdMap.get(intraId);
        }

        String url = apiBaseUrl + "/users.list";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(botToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<JsonNode> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            JsonNode body = response.getBody();

            if (body != null && body.get("ok").asBoolean()) {
                JsonNode members = body.get("members");
                if (members.isArray()) {
                    for (JsonNode member : members) {
                        if (member.has("name") && member.get("name").asText().equals(intraId)) {
                            return member.get("id").asText();
                        }
                    }
                }
            } else {
                log.warn("슬랙 유저 리스트 조회 실패 (응답): {}", body);
            }
        } catch (Exception e) {
            log.error("슬랙 API 호출 실패: {}", e.getMessage());
        }
        return null;
    }

    private void sendMessage(String channelId, String text) {
        String url = apiBaseUrl + "/chat.postMessage";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(botToken);

        Map<String, Object> body = new HashMap<>();
        body.put("channel", channelId);
        body.put("text", text);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<JsonNode> response =
                restTemplate.postForEntity(url, request, JsonNode.class);
        JsonNode responseBody = response.getBody();

        if (responseBody == null || !responseBody.get("ok").asBoolean()) {
            log.error("❌ DM 전송 실패! Target: {}, Error: {}", channelId, responseBody);
            return;
        }

        log.info("✅ DM 전송 성공! Target: {}", channelId);
    }
}
