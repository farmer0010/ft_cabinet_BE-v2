package com.gyeongsan.cabinet.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

class GlobalExceptionHandlerMappingTest {

    private static final Pattern LEAK =
            Pattern.compile(
                    "Exception|org\\.springframework|com\\.gyeongsan|java\\.|jakarta\\.|hibernate"
                            + "|\\bselect\\b|\\binsert\\b|\\bupdate\\b|\\bSQL\\b|\\bat \\w+\\.|stackTrace|\\.java",
                    Pattern.CASE_INSENSITIVE);

    record Body(String name) {}

    @RestController
    static class ProbeController {
        @PostMapping(path = "/t/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        String json(@RequestBody Body body) {
            return body.name();
        }

        @GetMapping("/t/int")
        String integer(@RequestParam("count") int count) {
            return String.valueOf(count);
        }

        @PostMapping(path = "/t/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String upload(@RequestPart("file") MultipartFile file) {
            return file.getOriginalFilename();
        }

        @GetMapping("/t/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException(
                    "com.gyeongsan.cabinet.domain.user.model.User", 7L);
        }

        @GetMapping("/t/lock-timeout")
        void lockTimeout() {
            throw new CannotAcquireLockException(
                    "could not execute statement [Lock wait timeout exceeded; try restarting transaction] "
                            + "[select u1_0.id from user u1_0 where u1_0.id=? for update]");
        }

        @GetMapping("/t/deadlock")
        void deadlock() {
            throw new DeadlockLoserDataAccessException(
                    "Deadlock found when trying to get lock", null);
        }

        @GetMapping("/t/attended")
        void attended() {
            throw new ServiceException(ErrorCode.ALREADY_ATTENDED);
        }

        @GetMapping("/t/illegal-state")
        void illegalState() {
            throw new IllegalStateException("내부 상태 org.springframework.Foo 가 이상합니다");
        }

        @GetMapping("/t/enum")
        void badEnum() {
            Enum.valueOf(java.util.concurrent.TimeUnit.class, "NOPE");
        }

        @GetMapping("/t/plain-illegal-argument")
        void plainIllegalArgument() {
            throw new IllegalArgumentException("되돌리기 사유(reason)는 필수입니다.");
        }

        @GetMapping("/t/too-large")
        void tooLarge() {
            throw new MaxUploadSizeExceededException(20L * 1024 * 1024);
        }

        @GetMapping("/t/constraint")
        void constraint() {
            throw new ConstraintViolationException(
                    "password must not be blank [com.gyeongsan.Foo]", Set.of());
        }

        @GetMapping("/t/bind")
        void bind() throws BindException {
            throw new BindException(new BeanPropertyBindingResult(new Object(), "request"));
        }

        @GetMapping("/t/denied")
        void denied() {
            throw new AccessDeniedException("Access Denied for com.gyeongsan.Secured");
        }

        @GetMapping("/t/integrity")
        void integrity() {
            throw new DataIntegrityViolationException(
                    "could not execute statement [Duplicate entry 'x' for key 'uk_oauth'] [insert into oauth_link ...]");
        }
    }

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(new ProbeController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private JsonNode expect(MvcResult result, int status) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(status);
        JsonNode json = mapper.readTree(body);
        assertThat(json.path("success").asBoolean(true)).isFalse();
        assertThat(json.path("status").asInt()).isEqualTo(status);
        assertThat(json.path("message").asText()).isNotBlank();
        assertThat(body).as("응답 본문에 내부 정보가 있으면 안 된다").doesNotContainPattern(LEAK);
        return json;
    }

    @Test
    @DisplayName("깨진 JSON 본문 → 400")
    void malformedJson() throws Exception {
        expect(
                mvc.perform(
                                post("/t/json")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\": "))
                        .andReturn(),
                400);
    }

    @Test
    @DisplayName("타입 불일치(?count=abc) → 400, 파라미터 이름만 알려 주고 값·타입 이름은 담지 않는다")
    void typeMismatch() throws Exception {
        JsonNode json =
                expect(mvc.perform(get("/t/int").param("count", "abc<script>")).andReturn(), 400);
        assertThat(json.path("message").asText())
                .contains("count")
                .doesNotContain("abc", "<script>");
    }

    @Test
    @DisplayName("필수 파라미터 누락 → 400")
    void missingParameter() throws Exception {
        JsonNode json = expect(mvc.perform(get("/t/int")).andReturn(), 400);
        assertThat(json.path("message").asText()).contains("count");
    }

    @Test
    @DisplayName("필수 멀티파트 파트 누락 → 400")
    void missingPart() throws Exception {
        expect(
                mvc.perform(
                                multipart("/t/upload")
                                        .file(
                                                new MockMultipartFile(
                                                        "other",
                                                        "a.txt",
                                                        "text/plain",
                                                        "x".getBytes())))
                        .andReturn(),
                400);
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type → 415")
    void unsupportedMediaType() throws Exception {
        expect(
                mvc.perform(post("/t/json").contentType(MediaType.TEXT_PLAIN).content("hello"))
                        .andReturn(),
                415);
    }

    @Test
    @DisplayName("업로드 크기 초과 → 413")
    void uploadTooLarge() throws Exception {
        expect(mvc.perform(get("/t/too-large")).andReturn(), 413);
    }

    @Test
    @DisplayName("존재하지 않는 경로 → 404 (500 이 아니다)")
    void unknownPath() throws Exception {
        MvcResult result = mvc.perform(get("/t/does-not-exist")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("핸들러 단위: NoResourceFound / NoHandlerFound 는 404 로 매핑된다")
    void notFoundHandlersReturn404() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        var r1 =
                handler.handleNotFound(
                        new org.springframework.web.servlet.resource.NoResourceFoundException(
                                org.springframework.http.HttpMethod.GET, "/x"));
        var r2 =
                handler.handleNotFound(
                        new org.springframework.web.servlet.NoHandlerFoundException(
                                "GET", "/x", new org.springframework.http.HttpHeaders()));
        assertThat(r1.getStatusCode().value()).isEqualTo(404);
        assertThat(r2.getStatusCode().value()).isEqualTo(404);
        assertThat(r1.getBody().getMessage()).doesNotContainPattern(LEAK);
    }

    @Test
    @DisplayName("낙관적 락 충돌(@Version) → 409, 엔티티 클래스 이름은 응답에 없다")
    void optimisticLockConflict() throws Exception {
        JsonNode json = expect(mvc.perform(get("/t/optimistic")).andReturn(), 409);
        assertThat(json.path("message").asText())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE.getMessage());
    }

    @Test
    @DisplayName("락 대기 초과·교착 → 409, SQL 은 응답에 없다")
    void pessimisticLockFailures() throws Exception {
        expect(mvc.perform(get("/t/lock-timeout")).andReturn(), 409);
        expect(mvc.perform(get("/t/deadlock")).andReturn(), 409);
    }

    @Test
    @DisplayName("출석 중복 → 409 (500 이 아니다)")
    void duplicateAttendance() throws Exception {
        JsonNode json = expect(mvc.perform(get("/t/attended")).andReturn(), 409);
        assertThat(json.path("message").asText()).isEqualTo("이미 오늘 출석체크를 완료했습니다.");
    }

    @Test
    @DisplayName("검증 실패(BindException, ConstraintViolationException) → 400, 위반 내용·클래스 이름 없음")
    void validationFailures() throws Exception {
        expect(mvc.perform(get("/t/bind")).andReturn(), 400);
        JsonNode json = expect(mvc.perform(get("/t/constraint")).andReturn(), 400);
        assertThat(json.path("message").asText()).doesNotContain("password");
    }

    @Test
    @DisplayName("컨트롤러 안에서 던져진 AccessDeniedException → 403 (500 이 아니다)")
    void accessDenied() throws Exception {
        expect(mvc.perform(get("/t/denied")).andReturn(), 403);
    }

    @Test
    @DisplayName("예상하지 못한 예외는 계속 500 이지만 내부 메시지는 응답에 없다")
    void unexpectedExceptionStays500WithoutDetails() throws Exception {
        JsonNode json = expect(mvc.perform(get("/t/illegal-state")).andReturn(), 500);
        assertThat(json.path("message").asText()).doesNotContain("org.springframework", "내부 상태");
    }

    @Test
    @DisplayName("IllegalArgumentException: 서비스가 쓴 안내 문구는 그대로, 라이브러리 내부 메시지는 일반 문구로")
    void illegalArgumentMessagesAreSanitized() throws Exception {
        JsonNode plain = expect(mvc.perform(get("/t/plain-illegal-argument")).andReturn(), 400);
        assertThat(plain.path("message").asText()).contains("되돌리기 사유(reason)는 필수입니다.");

        JsonNode internal = expect(mvc.perform(get("/t/enum")).andReturn(), 400);
        assertThat(internal.path("message").asText()).contains("요청 값이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("기존 매핑은 그대로: 데이터 무결성 위반 → 409 (SQL·제약 이름은 응답에 없음)")
    void existingDataIntegrityMappingUnchanged() throws Exception {
        expect(mvc.perform(get("/t/integrity")).andReturn(), 409);
    }

    @Test
    @DisplayName("clientSafeMessage 단위: 패키지 경로·예외 이름·스택 프레임·SQL 흔적은 가리고 일반 문장은 유지한다")
    void clientSafeMessageUnit() {
        String generic = "요청 값이 올바르지 않습니다.";
        for (String leaky :
                new String[] {
                    "No enum constant com.gyeongsan.cabinet.domain.cabinet.model.CabinetStatus.FOO",
                    "org.hibernate.HibernateException: boom",
                    "NumberFormatException: For input string",
                    "at com.gyeongsan.Foo.bar(Foo.java:10)",
                    "could not insert into user set name=?",
                    "",
                    null
                }) {
            assertThat(
                            GlobalExceptionHandler.clientSafeMessage(
                                    new IllegalArgumentException(leaky)))
                    .as(String.valueOf(leaky))
                    .isEqualTo(generic);
        }
        for (String fine :
                new String[] {
                    "사물함 번호는 1 이상이어야 합니다.",
                    "되돌리기 사유(reason)는 200자 이하여야 합니다.",
                    "지원하지 않는 작업 종류입니다: CABINET_BULK_STATUS_UPDATE"
                }) {
            assertThat(GlobalExceptionHandler.clientSafeMessage(new IllegalArgumentException(fine)))
                    .isEqualTo(fine);
        }
    }
}
