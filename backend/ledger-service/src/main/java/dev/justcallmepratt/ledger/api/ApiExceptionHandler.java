package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.app.ApiException;
import dev.justcallmepratt.ledger.app.FieldIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;

/** Maps every failure to an RFC 9457 problem with a stable {@code code} (and {@code errors} for validation). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage(), ex.errors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldIssue> errors = ex.getBindingResult().getAllErrors().stream()
                .map(e -> new FieldIssue(e instanceof FieldError fe ? fe.getField() : e.getObjectName(),
                        e.getDefaultMessage() == null ? "is invalid" : e.getDefaultMessage()))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Request validation failed", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Malformed or unreadable JSON request body",
                List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid value for parameter '" + ex.getName() + "'",
                List.of(new FieldIssue(ex.getName(), "has an invalid value")));
    }

    /** Spring's own request errors (404, 405, 415, missing parameter ...) keep their status. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleAny(Exception ex) {
        if (ex instanceof ErrorResponse framework) {
            HttpStatusCode status = framework.getStatusCode();
            ProblemDetail body = framework.getBody();
            body.setType(URI.create("urn:ledger-core:problem:" + codeFor(status)));
            body.setProperty("code", codeFor(status));
            return ResponseEntity.status(status).headers(framework.getHeaders()).body(body);
        }
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal server error", List.of());
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "invalid_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 415 -> "unsupported_media_type";
            default -> status.is4xxClientError() ? "invalid_request" : "internal_error";
        };
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String code, String detail,
                                                 List<FieldIssue> errors) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("urn:ledger-core:problem:" + code));
        body.setProperty("code", code);
        if (!errors.isEmpty()) {
            body.setProperty("errors", errors);
        }
        return ResponseEntity.status(status).body(body);
    }
}
