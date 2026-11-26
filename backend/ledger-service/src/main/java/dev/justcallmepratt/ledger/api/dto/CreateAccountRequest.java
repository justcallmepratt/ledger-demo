package dev.justcallmepratt.ledger.api.dto;

import dev.justcallmepratt.ledger.app.ApiException;
import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateAccountRequest(
        @NotNull UUID id,
        @NotNull @Positive Integer ledger,
        @NotNull @Min(1) @Max(65535) Integer code,
        @NotBlank @Size(max = 100) String name,
        @Valid AccountFlagsRequest flags) {

    /** An account snapshot with zero counters; {@code createdAt} is set by the service. */
    public Account toDomain() {
        AccountFlagsRequest f = flags == null ? new AccountFlagsRequest(null, null, null, null) : flags;
        if (Boolean.TRUE.equals(f.closed())) {
            throw ApiException.invalid("flags.closed", "is not allowed at creation");
        }
        return Account.open(id, ledger, code, name,
                new AccountFlags(Boolean.TRUE.equals(f.debitsMustNotExceedCredits()),
                        Boolean.TRUE.equals(f.creditsMustNotExceedDebits()),
                        Boolean.TRUE.equals(f.history()), false),
                null);
    }
}
