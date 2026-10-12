package com.gyeongsan.cabinet.global.exception;

import com.gyeongsan.cabinet.adapter.in.web.admin.dto.BulkStatusRejection;
import com.gyeongsan.cabinet.adapter.in.web.admin.dto.UndoRejection;
import com.gyeongsan.cabinet.common.ApiResponse;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.ConstraintViolationException;
import java.util.regex.Pattern;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Log4j2
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiResponse<?>> handleServiceException(ServiceException e) {
        ErrorCode ec = e.getErrorCode();
        log.warn("⚠️ 비즈니스 로직 예외 발생: [{}] {}", ec.getCode(), ec.getMessage());

        ApiResponse<?> response = ApiResponse.fail(ec.getStatus(), ec.getMessage());
        return ResponseEntity.status(ec.getStatus()).body(response);
    }

    @ExceptionHandler(BulkStatusUpdateRejectedException.class)
    public ResponseEntity<ApiResponse<BulkStatusRejection>> handleBulkStatusUpdateRejected(
            BulkStatusUpdateRejectedException e) {
        log.warn("⚠️ 사물함 일괄 변경 거부: {}", e.getMessage());

        ApiResponse<BulkStatusRejection> response =
                ApiResponse.fail(e.getStatus(), e.getMessage(), e.getRejection());
        return ResponseEntity.status(e.getStatus()).body(response);
    }

    @ExceptionHandler(UndoRejectedException.class)
    public ResponseEntity<ApiResponse<UndoRejection>> handleUndoRejected(UndoRejectedException e) {
        log.warn("⚠️ 되돌리기 거부: {}", e.getMessage());

        ApiResponse<UndoRejection> response =
                ApiResponse.fail(HttpStatus.CONFLICT, e.getMessage(), e.getRejection());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<String>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("⚠️ 잘못된 요청 감지: {}", e.getMessage());
        ApiResponse<String> response =
                ApiResponse.fail(HttpStatus.BAD_REQUEST, "❌ 에러: " + clientSafeMessage(e));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 이 서비스가 직접 쓴 안내 문구는 그대로 돌려주되, 라이브러리가 던진 {@link IllegalArgumentException}(예: {@code
     * Enum.valueOf} 의 "No enum constant com.…")처럼 패키지·클래스 이름이 보이는 메시지는 일반 문구로 바꾼다. 내부 구조가 응답으로 새지
     * 않게 한다.
     */
    static String clientSafeMessage(IllegalArgumentException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank() || INTERNAL_DETAIL.matcher(message).find()) {
            return "요청 값이 올바르지 않습니다.";
        }
        return message;
    }

    /** 패키지 경로, 예외 클래스 이름, 스택 프레임, SQL 흔적. */
    private static final Pattern INTERNAL_DETAIL =
            Pattern.compile(
                    "\\b(?:com|org|java|javax|jakarta|io|net)\\.[a-z_][\\w.$]*"
                            + "|\\w+(?:Exception|Error)\\b"
                            + "|\\bat [\\w.$]+\\("
                            + "|\\b(?:select|insert|update|delete)\\b[^.\\n]*\\b(?:from|into|set)\\b",
                    Pattern.CASE_INSENSITIVE);

    // --- 클라이언트 요청 오류: 400/404/406/413/415. 스택·요청 내용은 로그에 남기지 않고 한 줄만 남긴다. ---

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<String>> handleUnreadableBody(
            HttpMessageNotReadableException e) {
        return clientError(e, HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다. 형식(JSON)을 확인해 주세요.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<String>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        return clientError(
                e, HttpStatus.BAD_REQUEST, "요청 파라미터 '" + e.getName() + "' 의 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<String>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return clientError(
                e, HttpStatus.BAD_REQUEST, "필수 파라미터 '" + e.getParameterName() + "' 가 없습니다.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<String>> handleMissingPart(
            MissingServletRequestPartException e) {
        return clientError(
                e, HttpStatus.BAD_REQUEST, "필수 항목 '" + e.getRequestPartName() + "' 가 없습니다.");
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingRequestCookieException.class})
    public ResponseEntity<ApiResponse<String>> handleMissingHeaderOrCookie(Exception e) {
        return clientError(e, HttpStatus.BAD_REQUEST, "필수 요청 정보가 없습니다.");
    }

    @ExceptionHandler({
        BindException.class,
        ConstraintViolationException.class,
        HandlerMethodValidationException.class
    })
    public ResponseEntity<ApiResponse<String>> handleValidation(Exception e) {
        return clientError(e, HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<String>> handleUploadTooLarge(
            MaxUploadSizeExceededException e) {
        return clientError(e, HttpStatus.PAYLOAD_TOO_LARGE, "업로드할 수 있는 파일 크기를 초과했습니다.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<String>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException e) {
        return clientError(e, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<String>> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException e) {
        return clientError(e, HttpStatus.NOT_ACCEPTABLE, "요청한 응답 형식을 제공할 수 없습니다.");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<String>> handleNotFound(Exception e) {
        return clientError(e, HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<String>> handleAccessDenied(AccessDeniedException e) {
        return clientError(e, HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");
    }

    // --- 동시 갱신 충돌: 낙관적 락(@Version) 충돌, 락 대기 초과, 교착 → 409. 다시 시도하면 되는 상황이다. ---

    @ExceptionHandler({
        OptimisticLockingFailureException.class,
        PessimisticLockingFailureException.class
    })
    public ResponseEntity<ApiResponse<String>> handleConcurrentUpdate(Exception e) {
        ErrorCode ec = ErrorCode.CONCURRENT_UPDATE;
        log.warn("⚠️ 동시 갱신 충돌: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.fail(ec.getStatus(), ec.getMessage()));
    }

    private ResponseEntity<ApiResponse<String>> clientError(
            Exception e, HttpStatus status, String message) {
        // 요청 내용(본문, 파라미터 값)이 로그에 남지 않도록 예외 종류만 기록한다.
        log.warn("⚠️ 잘못된 요청 ({}): {}", status.value(), e.getClass().getSimpleName());
        return ResponseEntity.status(status).body(ApiResponse.fail(status, message));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<String>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        log.warn("⚠️ 지원하지 않는 메서드 요청: {}", e.getMessage());
        ApiResponse<String> response =
                ApiResponse.fail(
                        HttpStatus.METHOD_NOT_ALLOWED,
                        "❌ 지원하지 않는 요청 방식입니다. (GET/POST 등 메서드를 확인하세요)");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ApiResponse<String>> handleRateLimitException(RequestNotPermitted e) {
        log.warn("🛑 요청 제한 초과 (RateLimit): {}", e.getMessage());
        ApiResponse<String> response =
                ApiResponse.fail(
                        HttpStatus.TOO_MANY_REQUESTS, "너무 많은 요청을 보내셨습니다. 잠시 후 다시 시도해주세요. 🛑");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<String>> handleDataIntegrityViolationException(
            DataIntegrityViolationException e) {
        log.warn("⚠️ 데이터 무결성 예외 발생: {}", e.getMessage());
        ApiResponse<String> response =
                ApiResponse.fail(HttpStatus.CONFLICT, "이미 연동 처리 중이거나 사용 중인 소셜 계정입니다.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<String>> handleException(Exception e) {
        log.error("🔥 서버 내부 오류 발생: ", e);
        ApiResponse<String> response =
                ApiResponse.fail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "🔥 서버 오류가 발생했습니다. 관리자에게 문의하세요.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
