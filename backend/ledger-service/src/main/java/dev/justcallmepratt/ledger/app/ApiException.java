package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.domain.TransferResult;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

/** A business or request error that maps to an RFC 9457 problem with a stable {@code code}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldIssue> errors;

    public ApiException(HttpStatus status, String code, String detail, List<FieldIssue> errors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldIssue> errors() {
        return errors;
    }

    public static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", detail);
    }

    public static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "Request validation failed",
                List.of(new FieldIssue(field, message)));
    }

    public static ApiException accountNotFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account " + id + " does not exist");
    }

    public static ApiException transferNotFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "transfer_not_found", "Transfer " + id + " does not exist");
    }

    public static ApiException historyNotEnabled(UUID id) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "history_not_enabled",
                "Account " + id + " was created without the history flag");
    }

    public static ApiException ledgerNotFound(int ledger) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ledger_not_found",
                "Ledger " + ledger + " does not exist");
    }

    public static ApiException accountDiffers(String fields) {
        return new ApiException(HttpStatus.CONFLICT, "exists_with_different_fields",
                "An account with this id exists with different " + fields);
    }

    /** Maps a rejected transfer result to its HTTP status (single-transfer endpoint). */
    public static ApiException fromResult(TransferResult result, String detail) {
        HttpStatus status = switch (result.category()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BATCH_ONLY, SUCCESS -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        String message = detail != null ? detail : "Transfer rejected: " + result.code();
        return new ApiException(status, result.code(), message);
    }
}
