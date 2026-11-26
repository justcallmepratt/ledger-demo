package dev.justcallmepratt.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A transfer moves {@code amount} from one debit account to one credit account.
 *
 * <p>For post/void transfers the account ids, ledger and code may be omitted on input
 * (null / 0); they are inherited from the pending transfer. {@code seq} and
 * {@code timestamp} are server-set; {@code seq == 0} means "not stored yet".
 */
public record Transfer(UUID id,
                       UUID debitAccountId,
                       UUID creditAccountId,
                       long amount,
                       int ledger,
                       int code,
                       TransferFlags flags,
                       UUID pendingId,
                       int timeoutSeconds,
                       String reference,
                       long seq,
                       Instant timestamp,
                       PendingStatus pendingStatus,
                       Instant expiresAt) {

    public Transfer {
        Objects.requireNonNull(flags, "flags");
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must not be negative");
        }
    }

    /** A transfer as submitted by a client: no server-set fields. */
    public static Transfer request(UUID id, UUID debitAccountId, UUID creditAccountId, long amount,
                                   int ledger, int code, TransferFlags flags, UUID pendingId,
                                   int timeoutSeconds, String reference) {
        return new Transfer(id, debitAccountId, creditAccountId, amount, ledger, code, flags,
                pendingId, timeoutSeconds, reference, 0L, null, null, null);
    }

    public Transfer withStored(long seq, Instant timestamp) {
        return new Transfer(id, debitAccountId, creditAccountId, amount, ledger, code, flags,
                pendingId, timeoutSeconds, reference, seq, timestamp, pendingStatus, expiresAt);
    }

    public Transfer withPendingStatus(PendingStatus status) {
        return new Transfer(id, debitAccountId, creditAccountId, amount, ledger, code, flags,
                pendingId, timeoutSeconds, reference, seq, timestamp, status, expiresAt);
    }
}
