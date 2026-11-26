package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;

import java.time.Instant;
import java.util.UUID;

/** Domain objects for controller slice tests. */
final class Samples {

    static final Instant T = Instant.parse("2026-11-26T08:00:00Z");

    private Samples() {
    }

    static Account account(UUID id) {
        return new Account(id, 840, 1, "Alice wallet", new AccountFlags(true, false, true, false), 1, 2, 3, 4, T);
    }

    static Transfer transfer(UUID id, UUID debit, UUID credit, long amount) {
        return new Transfer(id, debit, credit, amount, 840, 1, TransferFlags.NONE, null, 0, "INV-1", 7L, T, null,
                null);
    }

    static Transfer pendingTransfer(UUID id, UUID debit, UUID credit, long amount) {
        return new Transfer(id, debit, credit, amount, 840, 1, TransferFlags.PENDING, null, 60, null, 8L, T,
                PendingStatus.PENDING, T.plusSeconds(60));
    }
}
