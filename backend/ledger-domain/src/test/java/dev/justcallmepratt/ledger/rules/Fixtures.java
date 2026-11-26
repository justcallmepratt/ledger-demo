package dev.justcallmepratt.ledger.rules;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;

import java.time.Instant;
import java.util.UUID;

final class Fixtures {

    static final Instant NOW = Instant.parse("2026-11-26T08:00:00Z");
    static final int USD = 840;

    private Fixtures() {
    }

    static UUID uuid(long n) {
        return new UUID(0x1000L, n);
    }

    static Account account(long n) {
        return account(n, USD, AccountFlags.NONE);
    }

    static Account account(long n, int ledger, AccountFlags flags) {
        return Account.open(uuid(n), ledger, 1, "acct-" + n, flags, NOW);
    }

    static Account funded(long n, AccountFlags flags, long dp, long dP, long cp, long cP) {
        return account(n, USD, flags).withCounters(dp, dP, cp, cP);
    }

    static Transfer single(long id, Account debit, Account credit, long amount) {
        return Transfer.request(uuid(id), debit.id(), credit.id(), amount, debit.ledger(), 1,
                TransferFlags.NONE, null, 0, null);
    }

    static Transfer pending(long id, Account debit, Account credit, long amount, int timeout) {
        return Transfer.request(uuid(id), debit.id(), credit.id(), amount, debit.ledger(), 1,
                TransferFlags.PENDING, null, timeout, null);
    }

    static Transfer post(long id, long pendingId, long amount) {
        return Transfer.request(uuid(id), null, null, amount, 0, 0, TransferFlags.POST, uuid(pendingId), 0, null);
    }

    static Transfer voided(long id, long pendingId) {
        return Transfer.request(uuid(id), null, null, 0, 0, 0, TransferFlags.VOID, uuid(pendingId), 0, null);
    }

    /** A stored pending transfer in the given status. */
    static Transfer storedPending(long id, Account debit, Account credit, long amount, PendingStatus status,
                                  Instant expiresAt) {
        return new Transfer(uuid(id), debit.id(), credit.id(), amount, debit.ledger(), 1, TransferFlags.PENDING,
                null, 0, null, 5L, NOW, status, expiresAt);
    }

    static AccountFlags debitLimited() {
        return new AccountFlags(true, false, false, false);
    }

    static AccountFlags creditLimited() {
        return new AccountFlags(false, true, false, false);
    }

    static AccountFlags closed() {
        return new AccountFlags(false, false, false, true);
    }
}
