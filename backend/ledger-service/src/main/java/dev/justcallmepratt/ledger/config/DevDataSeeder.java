package dev.justcallmepratt.ledger.config;

import dev.justcallmepratt.ledger.app.AccountService;
import dev.justcallmepratt.ledger.app.TransferService;
import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Seeds a few demo accounts and transfers in the {@code dev} profile. Ids are derived from
 * names and every write is idempotent, so restarting the app changes nothing.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final int USD = 840;

    private final AccountService accounts;
    private final TransferService transfers;

    public DevDataSeeder(AccountService accounts, TransferService transfers) {
        this.accounts = accounts;
        this.transfers = transfers;
    }

    static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("ledger-core-demo:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void run(ApplicationArguments args) {
        AccountFlags wallet = new AccountFlags(true, false, true, false);
        account("treasury", 1, "Treasury (funding source)", AccountFlags.NONE);
        account("alice", 2, "Alice wallet", wallet);
        account("bob", 2, "Bob wallet", wallet);
        account("merchant", 3, "Merchant settlement", new AccountFlags(false, false, true, false));
        account("fees", 4, "Fee revenue", AccountFlags.NONE);

        List<Transfer> batch = new ArrayList<>();
        batch.add(single("fund-alice", "treasury", "alice", 100_000, "demo-funding"));
        batch.add(single("fund-bob", "treasury", "bob", 50_000, "demo-funding"));
        batch.add(single("alice-pays-inv-1001", "alice", "merchant", 2_599, "INV-1001"));
        // an atomic pair: payment and fee succeed or fail together
        batch.add(linked(single("alice-pays-inv-1002", "alice", "merchant", 1_200, "INV-1002")));
        batch.add(single("fee-inv-1002", "merchant", "fees", 36, "INV-1002"));
        // authorise 700, capture 500 (partial post releases the other 200)
        batch.add(pending("bob-auth-inv-1003", "bob", "merchant", 700, 3_600, "INV-1003"));
        batch.add(Transfer.request(id("bob-capture-inv-1003"), null, null, 500, 0, 0, TransferFlags.POST,
                id("bob-auth-inv-1003"), 0, "INV-1003"));
        // authorise 1000 and leave it open for the pending view
        batch.add(pending("bob-auth-inv-1004", "bob", "merchant", 1_000, 3_600, "INV-1004"));

        transfers.submitBatch(batch).forEach(r -> log.info("demo transfer {} -> {}", r.index(), r.code().code()));
    }

    private void account(String key, int code, String name, AccountFlags flags) {
        accounts.create(Account.open(id(key), USD, code, name, flags, null));
    }

    private static Transfer single(String key, String debit, String credit, long amount, String reference) {
        return Transfer.request(id(key), id(debit), id(credit), amount, USD, 10, TransferFlags.NONE, null, 0,
                reference);
    }

    private static Transfer linked(Transfer t) {
        return Transfer.request(t.id(), t.debitAccountId(), t.creditAccountId(), t.amount(), t.ledger(), t.code(),
                t.flags().withLinked(true), t.pendingId(), t.timeoutSeconds(), t.reference());
    }

    private static Transfer pending(String key, String debit, String credit, long amount, int timeout,
                                    String reference) {
        return Transfer.request(id(key), id(debit), id(credit), amount, USD, 20, TransferFlags.PENDING, null,
                timeout, reference);
    }
}
