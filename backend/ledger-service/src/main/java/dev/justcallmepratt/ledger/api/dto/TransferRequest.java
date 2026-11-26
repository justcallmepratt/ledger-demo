package dev.justcallmepratt.ledger.api.dto;

import dev.justcallmepratt.ledger.app.ApiException;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Transfer as submitted. {@code amount} is a decimal integer string; for post/void the
 * account ids, ledger, code and amount may be omitted (inherited from the pending transfer).
 * Server-set fields (seq, timestamp, pendingStatus, expiresAt) are ignored if sent.
 */
public record TransferRequest(
        @NotNull UUID id,
        UUID debitAccountId,
        UUID creditAccountId,
        @Pattern(regexp = "^(0|[1-9][0-9]{0,18})$", message = "must be a non-negative integer string")
        String amount,
        @Min(1) Integer ledger,
        @Min(1) @Max(65535) Integer code,
        @Valid TransferFlagsRequest flags,
        UUID pendingId,
        @Min(0) @Max(31_536_000) Integer timeoutSeconds,
        @Size(max = 64) String reference) {

    public Transfer toDomain() {
        TransferFlags f = flags == null ? TransferFlags.NONE
                : new TransferFlags(Boolean.TRUE.equals(flags.linked()), Boolean.TRUE.equals(flags.pending()),
                Boolean.TRUE.equals(flags.postPendingTransfer()), Boolean.TRUE.equals(flags.voidPendingTransfer()));
        long minor = 0;
        if (amount != null) {
            try {
                minor = Long.parseLong(amount);
            } catch (NumberFormatException e) {
                throw ApiException.invalid("amount", "must fit in a signed 64-bit integer");
            }
        }
        if (!f.resolves()) {
            require(debitAccountId, "debitAccountId");
            require(creditAccountId, "creditAccountId");
            require(ledger, "ledger");
            require(code, "code");
        }
        return Transfer.request(id, debitAccountId, creditAccountId, minor, ledger == null ? 0 : ledger,
                code == null ? 0 : code, f, pendingId, timeoutSeconds == null ? 0 : timeoutSeconds, reference);
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw ApiException.invalid(field, "is required unless the transfer posts or voids a pending transfer");
        }
    }
}
