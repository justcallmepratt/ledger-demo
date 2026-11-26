package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.events.EventFactory;
import dev.justcallmepratt.ledger.persistence.AccountRepository;
import dev.justcallmepratt.ledger.persistence.BalanceHistoryEntry;
import dev.justcallmepratt.ledger.persistence.BalanceHistoryRepository;
import dev.justcallmepratt.ledger.persistence.LedgerRepository;
import dev.justcallmepratt.ledger.persistence.OutboxRepository;
import dev.justcallmepratt.ledger.domain.Ledger;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.persistence.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    /** Outcome of an idempotent create: {@code created} is false for an identical replay. */
    public record CreateResult(Account account, boolean created) {
    }

    private final AccountRepository accounts;
    private final LedgerRepository ledgers;
    private final TransferRepository transfers;
    private final BalanceHistoryRepository history;
    private final OutboxRepository outbox;
    private final EventFactory events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AccountService(AccountRepository accounts, LedgerRepository ledgers, TransferRepository transfers,
                          BalanceHistoryRepository history, OutboxRepository outbox, EventFactory events,
                          PlatformTransactionManager txManager, Clock clock) {
        this.accounts = accounts;
        this.ledgers = ledgers;
        this.transfers = transfers;
        this.history = history;
        this.outbox = outbox;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public List<Ledger> ledgers() {
        return ledgers.findAll();
    }

    /**
     * Creates the account, idempotently. Same id and same fields returns the stored account;
     * same id with different fields is a conflict. Concurrent duplicates are resolved by the
     * primary key (insert-if-absent), then re-read and compared.
     */
    public CreateResult create(Account requested) {
        Optional<Account> existing = accounts.findById(requested.id());
        if (existing.isPresent()) {
            return compare(existing.get(), requested);
        }
        if (!ledgers.exists(requested.ledger())) {
            throw ApiException.ledgerNotFound(requested.ledger());
        }
        Instant now = Times.now(clock);
        Account account = Account.open(requested.id(), requested.ledger(), requested.code(), requested.name(),
                requested.flags(), now);
        Boolean inserted = tx.execute(status -> {
            if (!accounts.insertIfAbsent(account)) {
                return false;
            }
            outbox.insert(events.accountCreated(account, now));
            return true;
        });
        if (Boolean.TRUE.equals(inserted)) {
            return new CreateResult(account, true);
        }
        Account stored = accounts.findById(requested.id())
                .orElseThrow(() -> new IllegalStateException("account vanished after insert conflict"));
        return compare(stored, requested);
    }

    private static CreateResult compare(Account stored, Account requested) {
        List<String> diff = new ArrayList<>();
        if (stored.ledger() != requested.ledger()) {
            diff.add("ledger");
        }
        if (stored.code() != requested.code()) {
            diff.add("code");
        }
        if (!stored.name().equals(requested.name())) {
            diff.add("name");
        }
        if (!stored.flags().equals(requested.flags())) {
            diff.add("flags");
        }
        if (!diff.isEmpty()) {
            throw ApiException.accountDiffers(String.join(", ", diff));
        }
        return new CreateResult(stored, false);
    }

    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.accountNotFound(id));
    }

    public Page<Account> list(int limit, String cursor) {
        Cursors.checkLimit(limit);
        List<Account> rows;
        if (cursor == null) {
            rows = accounts.page(null, null, limit + 1);
        } else {
            Object[] key = Cursors.decodeAccount(cursor);
            rows = accounts.page((Instant) key[0], (UUID) key[1], limit + 1);
        }
        boolean more = rows.size() > limit;
        List<Account> items = more ? rows.subList(0, limit) : rows;
        String next = more ? Cursors.encodeAccount(items.get(items.size() - 1).createdAt(),
                items.get(items.size() - 1).id()) : null;
        return new Page<>(List.copyOf(items), next);
    }

    public Page<Transfer> transfers(UUID accountId, int limit, String cursor) {
        Cursors.checkLimit(limit);
        get(accountId);
        long before = cursor == null ? Long.MAX_VALUE : Cursors.decodeLong(cursor);
        List<Transfer> rows = transfers.pageForAccount(accountId, before, limit + 1);
        boolean more = rows.size() > limit;
        List<Transfer> items = more ? rows.subList(0, limit) : rows;
        return new Page<>(List.copyOf(items), more ? Cursors.encodeLong(items.get(items.size() - 1).seq()) : null);
    }

    public Page<BalanceHistoryEntry> balances(UUID accountId, int limit, String cursor) {
        Cursors.checkLimit(limit);
        Account account = get(accountId);
        if (!account.flags().history()) {
            throw ApiException.historyNotEnabled(accountId);
        }
        long before = cursor == null ? Long.MAX_VALUE : Cursors.decodeLong(cursor);
        List<BalanceHistoryEntry> rows = history.page(accountId, before, limit + 1);
        boolean more = rows.size() > limit;
        List<BalanceHistoryEntry> items = more ? rows.subList(0, limit) : rows;
        return new Page<>(List.copyOf(items), more ? Cursors.encodeLong(items.get(items.size() - 1).id()) : null);
    }
}
