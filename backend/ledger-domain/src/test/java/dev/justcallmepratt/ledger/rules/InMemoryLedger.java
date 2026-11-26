package dev.justcallmepratt.ledger.rules;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferResult;
import dev.justcallmepratt.ledger.rules.TransferEngine.Applied;
import dev.justcallmepratt.ledger.rules.TransferEngine.Outcome;
import dev.justcallmepratt.ledger.rules.TransferEngine.Rejected;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A reference model of the service: idempotency by id, then the pure engine. Test-only. */
final class InMemoryLedger {

    final Map<UUID, Account> accounts = new LinkedHashMap<>();
    final Map<UUID, Transfer> transfers = new LinkedHashMap<>();
    private long seq = 0;

    void add(Account account) {
        accounts.put(account.id(), account);
    }

    /** Submits one transfer; returns the result code. */
    TransferResult submit(Transfer request, Instant now) {
        Transfer existing = transfers.get(request.id());
        if (existing != null) {
            Transfer pendingRef = request.pendingId() == null ? null : transfers.get(request.pendingId());
            Transfer normalized = TransferEngine.normalize(request, pendingRef);
            return TransferEngine.differingFields(existing, normalized).isEmpty()
                    ? TransferResult.EXISTS : TransferResult.EXISTS_WITH_DIFFERENT_FIELDS;
        }
        Transfer pending = request.pendingId() == null ? null : transfers.get(request.pendingId());
        UUID debitId = request.debitAccountId();
        UUID creditId = request.creditAccountId();
        if (request.flags().resolves() && pending != null) {
            debitId = pending.debitAccountId();
            creditId = pending.creditAccountId();
        }
        Account debit = debitId == null ? null : accounts.get(debitId);
        Account credit = creditId == null ? null : accounts.get(creditId);
        Outcome outcome = TransferEngine.apply(request, debit, credit, pending, now);
        if (outcome instanceof Rejected rejected) {
            return rejected.code();
        }
        Applied applied = (Applied) outcome;
        accounts.put(applied.debit().id(), applied.debit());
        accounts.put(applied.credit().id(), applied.credit());
        Transfer stored = applied.transfer().withStored(++seq, now);
        transfers.put(stored.id(), stored);
        if (applied.resolvedStatus() != null && pending != null) {
            transfers.put(pending.id(), pending.withPendingStatus(applied.resolvedStatus()));
        }
        return TransferResult.CREATED;
    }

    /** Expires every open pending transfer due at {@code now}. */
    int expireDue(Instant now) {
        int count = 0;
        for (Transfer t : new ArrayList<>(transfers.values())) {
            if (t.pendingStatus() == PendingStatus.PENDING && t.expiresAt() != null && !now.isBefore(t.expiresAt())) {
                Outcome o = TransferEngine.expire(t, accounts.get(t.debitAccountId()), accounts.get(t.creditAccountId()), now);
                Applied applied = (Applied) o;
                accounts.put(applied.debit().id(), applied.debit());
                accounts.put(applied.credit().id(), applied.credit());
                transfers.put(t.id(), applied.transfer());
                count++;
            }
        }
        return count;
    }

    long sum(int ledger, java.util.function.ToLongFunction<Account> counter) {
        long total = 0;
        for (Account a : accounts.values()) {
            if (a.ledger() == ledger) {
                total += counter.applyAsLong(a);
            }
        }
        return total;
    }

    List<Transfer> pendingOpen() {
        List<Transfer> result = new ArrayList<>();
        for (Transfer t : transfers.values()) {
            if (t.pendingStatus() == PendingStatus.PENDING) {
                result.add(t);
            }
        }
        return result;
    }
}
