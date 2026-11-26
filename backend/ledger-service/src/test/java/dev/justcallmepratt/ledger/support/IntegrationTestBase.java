package dev.justcallmepratt.ledger.support;

import dev.justcallmepratt.ledger.app.AccountService;
import dev.justcallmepratt.ledger.app.TransferService;
import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

/**
 * Base for integration tests: the full application on a real (embedded) PostgreSQL that is
 * started once per JVM. Schedulers and Kafka are off by default so tests drive jobs directly;
 * a subclass can override with its own {@code @TestPropertySource}. The database is emptied
 * before every test (the immutability trigger is disabled just for that cleanup).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
@TestPropertySource(properties = {
        "ledger.scheduling.enabled=false",
        "ledger.kafka.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=12"
})
public abstract class IntegrationTestBase {

    public static final int USD = 840;

    @Autowired protected AccountService accountService;
    @Autowired protected TransferService transferService;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected MockMvc mockMvc;
    @Autowired protected MutableClock clock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("ALTER TABLE transfers DISABLE TRIGGER USER").update();
        jdbc.sql("ALTER TABLE account_balance_history DISABLE TRIGGER USER").update();
        jdbc.sql("TRUNCATE accounts, transfers, pending_transfers, account_balance_history, outbox, activity "
                + "RESTART IDENTITY").update();
        jdbc.sql("ALTER TABLE transfers ENABLE TRIGGER USER").update();
        jdbc.sql("ALTER TABLE account_balance_history ENABLE TRIGGER USER").update();
        clock.set(TestClockConfig.START);
    }

    protected Account newAccount(AccountFlags flags) {
        return accountService.create(Account.open(UUID.randomUUID(), USD, 1, "test account", flags, null)).account();
    }

    protected Account newAccount() {
        return newAccount(AccountFlags.NONE);
    }

    protected Account reload(Account account) {
        return accountService.get(account.id());
    }

    protected static Transfer single(Account debit, Account credit, long amount) {
        return Transfer.request(UUID.randomUUID(), debit.id(), credit.id(), amount, USD, 1, TransferFlags.NONE,
                null, 0, null);
    }

    protected static Transfer pending(Account debit, Account credit, long amount, int timeoutSeconds) {
        return Transfer.request(UUID.randomUUID(), debit.id(), credit.id(), amount, USD, 1, TransferFlags.PENDING,
                null, timeoutSeconds, null);
    }

    protected static Transfer post(UUID pendingId, long amount) {
        return Transfer.request(UUID.randomUUID(), null, null, amount, 0, 0, TransferFlags.POST, pendingId, 0, null);
    }

    protected static Transfer voidOf(UUID pendingId) {
        return Transfer.request(UUID.randomUUID(), null, null, 0, 0, 0, TransferFlags.VOID, pendingId, 0, null);
    }

    protected static Transfer linked(Transfer t) {
        return Transfer.request(t.id(), t.debitAccountId(), t.creditAccountId(), t.amount(), t.ledger(), t.code(),
                t.flags().withLinked(true), t.pendingId(), t.timeoutSeconds(), t.reference());
    }

    /** Moves money from an unrestricted source account into the target. */
    protected void fund(Account target, long amount) {
        Account source = newAccount();
        TransferService.ItemResult result = transferService.submit(single(source, target, amount));
        if (result.code() != dev.justcallmepratt.ledger.domain.TransferResult.CREATED) {
            throw new IllegalStateException("funding failed: " + result.code());
        }
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** Polls until the condition holds or the timeout passes. */
    protected static boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
