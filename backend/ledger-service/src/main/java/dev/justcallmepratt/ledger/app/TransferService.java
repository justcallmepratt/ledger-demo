package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferResult;
import dev.justcallmepratt.ledger.events.EventFactory;
import dev.justcallmepratt.ledger.events.EventType;
import dev.justcallmepratt.ledger.persistence.AccountRepository;
import dev.justcallmepratt.ledger.persistence.BalanceHistoryRepository;
import dev.justcallmepratt.ledger.persistence.OutboxRepository;
import dev.justcallmepratt.ledger.persistence.TransferRepository;
import dev.justcallmepratt.ledger.rules.TransferEngine;
import dev.justcallmepratt.ledger.rules.TransferEngine.Applied;
import dev.justcallmepratt.ledger.rules.TransferEngine.Outcome;
import dev.justcallmepratt.ledger.rules.TransferEngine.Rejected;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates transfers. One READ COMMITTED transaction per linked chain: lock every account
 * the chain touches in ascending id order, apply the pure domain rules in memory, write the
 * counters, the transfers, balance history and outbox rows, commit. A failure anywhere in a
 * chain rolls the whole chain back.
 */
@Service
public class TransferService {

    /** Result for one submitted transfer. {@code transfer} is set for created/exists. */
    public record ItemResult(int index, UUID id, TransferResult code, Transfer transfer, String detail) {
    }

    private static final int EXPIRY_BATCH = 100;

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final BalanceHistoryRepository history;
    private final OutboxRepository outbox;
    private final EventFactory events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TransferService(AccountRepository accounts, TransferRepository transfers,
                           BalanceHistoryRepository history, OutboxRepository outbox, EventFactory events,
                           PlatformTransactionManager txManager, Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.history = history;
        this.outbox = outbox;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ writes

    /** A single transfer: must not be {@code linked}. */
    public ItemResult submit(Transfer request) {
        if (request.flags().linked()) {
            return new ItemResult(0, request.id(), TransferResult.LINKED_NOT_ALLOWED, null,
                    "linked is only allowed in POST /transfers/batch");
        }
        return runChain(List.of(request), 0).get(0);
    }

    /**
     * A batch. A run of transfers with {@code linked} set, ending with the first one without
     * it, is one atomic chain; separate chains are independent. A batch that ends inside an
     * open chain fails that chain with {@code linked_event_chain_open}.
     */
    public List<ItemResult> submitBatch(List<Transfer> requests) {
        List<ItemResult> results = new ArrayList<>(requests.size());
        int i = 0;
        int n = requests.size();
        while (i < n) {
            int end = i;
            while (end < n && requests.get(end).flags().linked()) {
                end++;
            }
            if (end >= n) {
                for (int k = i; k < n; k++) {
                    TransferResult code = k == n - 1 ? TransferResult.LINKED_EVENT_CHAIN_OPEN
                            : TransferResult.LINKED_EVENT_FAILED;
                    results.add(new ItemResult(k, requests.get(k).id(), code, null, null));
                }
                break;
            }
            results.addAll(runChain(requests.subList(i, end + 1), i));
            i = end + 1;
        }
        return results;
    }

    private List<ItemResult> runChain(List<Transfer> chain, int offset) {
        Instant now = Times.now(clock);
        return tx.execute(status -> {
            List<ItemResult> results = executeChain(chain, offset, now);
            boolean failed = chain.size() > 1
                    ? results.stream().anyMatch(r -> !isChainSuccess(r.code()))
                    : !results.get(0).code().isSuccess();
            if (failed) {
                status.setRollbackOnly();
            }
            return results;
        });
    }

    private static boolean isChainSuccess(TransferResult code) {
        return code == TransferResult.CREATED || code == TransferResult.EXISTS;
    }

    private List<ItemResult> executeChain(List<Transfer> chain, int offset, Instant now) {
        Map<UUID, Account> working = accounts.lockForUpdate(accountIdsToLock(chain));

        // A chain that was already committed in full is a pure replay: all items "exists".
        List<ItemResult> replay = tryFullReplay(chain, offset);
        if (replay != null) {
            return replay;
        }

        List<ItemResult> done = new ArrayList<>();
        Set<UUID> touched = new LinkedHashSet<>();
        for (int k = 0; k < chain.size(); k++) {
            ItemResult item = executeOne(chain.get(k), offset + k, now, working, touched);
            done.add(item);
            // In a multi-item chain only CREATED counts as success: an item that already exists
            // (while others do not) breaks the chain, like any other failure.
            boolean ok = chain.size() == 1 ? item.code().isSuccess() : item.code() == TransferResult.CREATED;
            if (!ok) {
                return failChain(chain, offset, k, item);
            }
        }
        for (UUID id : touched) {
            accounts.updateCounters(working.get(id));
        }
        return done;
    }

    /** First failing item keeps its code; every other item of the chain is linked_event_failed. */
    private static List<ItemResult> failChain(List<Transfer> chain, int offset, int failedIndex, ItemResult failed) {
        List<ItemResult> results = new ArrayList<>(chain.size());
        for (int k = 0; k < chain.size(); k++) {
            if (k == failedIndex) {
                results.add(failed);
            } else {
                results.add(new ItemResult(offset + k, chain.get(k).id(), TransferResult.LINKED_EVENT_FAILED, null,
                        null));
            }
        }
        return results;
    }

    private List<ItemResult> tryFullReplay(List<Transfer> chain, int offset) {
        List<ItemResult> results = new ArrayList<>(chain.size());
        for (int k = 0; k < chain.size(); k++) {
            Transfer request = chain.get(k);
            if (request.id() == null) {
                return null;
            }
            Optional<Transfer> stored = transfers.findById(request.id());
            if (stored.isEmpty()) {
                return null;
            }
            ItemResult r = compareWithStored(request, stored.get(), offset + k);
            if (r.code() != TransferResult.EXISTS) {
                return null;
            }
            results.add(r);
        }
        return results;
    }

    private ItemResult compareWithStored(Transfer request, Transfer stored, int index) {
        Transfer pending = request.pendingId() == null ? null : transfers.findById(request.pendingId()).orElse(null);
        List<String> diff = TransferEngine.differingFields(stored, TransferEngine.normalize(request, pending));
        if (diff.isEmpty()) {
            return new ItemResult(index, request.id(), TransferResult.EXISTS, stored, null);
        }
        return new ItemResult(index, request.id(), TransferResult.EXISTS_WITH_DIFFERENT_FIELDS, null,
                "A transfer with this id exists with different fields: " + String.join(", ", diff));
    }

    /** Accounts the chain may touch; post/void inherit theirs from the pending transfer. */
    private Set<UUID> accountIdsToLock(List<Transfer> chain) {
        Map<UUID, Transfer> pendingInChain = new LinkedHashMap<>();
        for (Transfer t : chain) {
            if (t.flags().pending() && t.id() != null) {
                pendingInChain.put(t.id(), t);
            }
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (Transfer t : chain) {
            Transfer pending = null;
            if (t.flags().resolves() && t.pendingId() != null) {
                pending = pendingInChain.get(t.pendingId());
                if (pending == null) {
                    pending = transfers.findById(t.pendingId()).orElse(null);
                }
            }
            if (pending != null) {
                ids.add(pending.debitAccountId());
                ids.add(pending.creditAccountId());
            } else {
                addIfPresent(ids, t.debitAccountId());
                addIfPresent(ids, t.creditAccountId());
            }
        }
        return ids;
    }

    private static void addIfPresent(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private ItemResult executeOne(Transfer request, int index, Instant now, Map<UUID, Account> working,
                                  Set<UUID> touched) {
        UUID id = request.id();
        if (id != null) {
            Optional<Transfer> existing = transfers.findById(id);
            if (existing.isPresent()) {
                return compareWithStored(request, existing.get(), index);
            }
        }
        Transfer pending = request.pendingId() == null ? null : transfers.findById(request.pendingId()).orElse(null);
        UUID debitId = request.debitAccountId();
        UUID creditId = request.creditAccountId();
        if (request.flags().resolves() && pending != null) {
            debitId = pending.debitAccountId();
            creditId = pending.creditAccountId();
        }
        Account debit = account(working, debitId);
        Account credit = account(working, creditId);

        Outcome outcome = TransferEngine.apply(request, debit, credit, pending, now);
        if (outcome instanceof Rejected rejected) {
            return new ItemResult(index, id, rejected.code(), null, rejected.detail());
        }
        Applied applied = (Applied) outcome;

        Optional<Long> seq = transfers.insert(applied.transfer());
        if (seq.isEmpty()) {
            // Lost a race on the primary key: the winner committed first. Re-read and compare.
            Transfer winner = transfers.findById(id)
                    .orElseThrow(() -> new IllegalStateException("transfer vanished after insert conflict"));
            return compareWithStored(request, winner, index);
        }
        Transfer stored = applied.transfer().withStored(seq.get(), now);
        if (stored.flags().pending()) {
            transfers.insertPending(stored.id(), stored.seq(), stored.expiresAt());
        }
        if (applied.resolvedStatus() != null) {
            if (!transfers.resolvePending(pending.id(), applied.resolvedStatus(), stored.id(), now)) {
                throw new IllegalStateException("pending transfer " + pending.id()
                        + " was resolved concurrently despite the account locks");
            }
        }
        working.put(applied.debit().id(), applied.debit());
        working.put(applied.credit().id(), applied.credit());
        touched.add(applied.debit().id());
        touched.add(applied.credit().id());
        recordHistory(applied.debit(), stored, now);
        recordHistory(applied.credit(), stored, now);
        outbox.insert(events.transfer(eventTypeFor(stored), stored, applied.debit(), applied.credit(), now));
        return new ItemResult(index, id, TransferResult.CREATED, stored, null);
    }

    private Account account(Map<UUID, Account> working, UUID id) {
        if (id == null) {
            return null;
        }
        Account known = working.get(id);
        if (known != null) {
            return known;
        }
        // Not locked up front (e.g. a pending transfer that appeared after the chain started).
        Account locked = accounts.lockForUpdate(List.of(id)).get(id);
        if (locked != null) {
            working.put(id, locked);
        }
        return locked;
    }

    private void recordHistory(Account after, Transfer transfer, Instant now) {
        if (after.flags().history()) {
            history.insert(after, transfer.id(), transfer.seq(), now);
        }
    }

    private static EventType eventTypeFor(Transfer t) {
        if (t.flags().postPendingTransfer()) {
            return EventType.TRANSFER_POSTED;
        }
        if (t.flags().voidPendingTransfer()) {
            return EventType.TRANSFER_VOIDED;
        }
        return EventType.TRANSFER_CREATED;
    }

    // ------------------------------------------------------------------ expiry

    /** Ids of pending transfers that are due to expire. */
    public List<UUID> dueForExpiry() {
        return transfers.findDueIds(Times.now(clock), EXPIRY_BATCH);
    }

    /**
     * Expires one pending transfer: releases its pending amounts, marks it EXPIRED and emits
     * {@code transfer.expired}. Returns false if it was resolved in the meantime.
     */
    public boolean expire(UUID transferId) {
        Instant now = Times.now(clock);
        Boolean expired = tx.execute(status -> {
            Transfer first = transfers.findById(transferId).orElse(null);
            if (first == null || first.pendingStatus() != PendingStatus.PENDING) {
                return false;
            }
            Map<UUID, Account> locked = accounts.lockForUpdate(List.of(first.debitAccountId(),
                    first.creditAccountId()));
            // Re-read under the account locks: a post/void holds the same locks, so this is stable.
            Transfer pending = transfers.findById(transferId).orElse(null);
            if (pending == null || pending.pendingStatus() != PendingStatus.PENDING) {
                return false;
            }
            Outcome outcome = TransferEngine.expire(pending, locked.get(pending.debitAccountId()),
                    locked.get(pending.creditAccountId()), now);
            if (!(outcome instanceof Applied applied)) {
                return false;
            }
            if (!transfers.resolvePending(pending.id(), PendingStatus.EXPIRED, null, now)) {
                return false;
            }
            accounts.updateCounters(applied.debit());
            accounts.updateCounters(applied.credit());
            recordHistory(applied.debit(), pending, now);
            recordHistory(applied.credit(), pending, now);
            outbox.insert(events.transfer(EventType.TRANSFER_EXPIRED, applied.transfer(), applied.debit(),
                    applied.credit(), now));
            return true;
        });
        return Boolean.TRUE.equals(expired);
    }

    // ------------------------------------------------------------------ reads

    public Transfer get(UUID id) {
        return transfers.findById(id).orElseThrow(() -> ApiException.transferNotFound(id));
    }

    public Page<Transfer> list(PendingStatus status, int limit, String cursor) {
        Cursors.checkLimit(limit);
        long before = cursor == null ? Long.MAX_VALUE : Cursors.decodeLong(cursor);
        List<Transfer> rows = transfers.page(before, status, limit + 1);
        boolean more = rows.size() > limit;
        List<Transfer> items = more ? rows.subList(0, limit) : rows;
        return new Page<>(List.copyOf(items), more ? Cursors.encodeLong(items.get(items.size() - 1).seq()) : null);
    }
}
