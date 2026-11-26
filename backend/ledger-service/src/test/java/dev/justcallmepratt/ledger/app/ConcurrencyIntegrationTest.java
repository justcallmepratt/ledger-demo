package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import dev.justcallmepratt.ledger.domain.TransferResult;
import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** N threads hammering the same accounts: exact balances, no deadlocks, books balanced. */
class ConcurrencyIntegrationTest extends IntegrationTestBase {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 200;
    private static final int ACCOUNTS = 4;

    @Autowired ReconciliationService reconciliation;

    @Test
    void manyThreadsOverFourAccountsEndWithExactBalances() throws Exception {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            accounts.add(newAccount());
        }
        long[] expectedDebits = new long[ACCOUNTS];
        long[] expectedCredits = new long[ACCOUNTS];
        for (int t = 0; t < THREADS; t++) {
            for (int i = 0; i < PER_THREAD; i++) {
                int d = (t + i) % ACCOUNTS;
                int c = (t + i + 1 + (i % 2)) % ACCOUNTS;   // some transfers go the "other way round"
                if (d == c) {
                    c = (c + 1) % ACCOUNTS;
                }
                long amount = 1 + (i % 7);
                expectedDebits[d] += amount;
                expectedCredits[c] += amount;
            }
        }

        AtomicInteger failures = new AtomicInteger();
        runThreads(THREADS, t -> {
            for (int i = 0; i < PER_THREAD; i++) {
                int d = (t + i) % ACCOUNTS;
                int c = (t + i + 1 + (i % 2)) % ACCOUNTS;
                if (d == c) {
                    c = (c + 1) % ACCOUNTS;
                }
                Transfer transfer = single(accounts.get(d), accounts.get(c), 1 + (i % 7));
                if (transferService.submit(transfer).code() != TransferResult.CREATED) {
                    failures.incrementAndGet();
                }
            }
        });

        assertThat(failures.get()).isZero();
        for (int i = 0; i < ACCOUNTS; i++) {
            Account a = reload(accounts.get(i));
            assertThat(a.debitsPosted()).as("debits of account %d", i).isEqualTo(expectedDebits[i]);
            assertThat(a.creditsPosted()).as("credits of account %d", i).isEqualTo(expectedCredits[i]);
        }
        assertThat(count("transfers")).isEqualTo((long) THREADS * PER_THREAD);
        ReconciliationReport report = reconciliation.run();
        assertThat(report.balanced()).isTrue();
        assertThat(report.accountMismatches()).isEmpty();
    }

    @Test
    void aLimitGuardedAccountNeverGoesBelowItsBound() throws Exception {
        Account guarded = newAccount(new AccountFlags(true, false, false, false));
        Account sink = newAccount();
        fund(guarded, 1000);
        AtomicInteger created = new AtomicInteger();

        runThreads(THREADS, t -> {
            for (int i = 0; i < 50; i++) {
                TransferResult code = transferService.submit(single(guarded, sink, 10)).code();
                if (code == TransferResult.CREATED) {
                    created.incrementAndGet();
                } else {
                    assertThat(code).isEqualTo(TransferResult.EXCEEDS_CREDITS);
                }
            }
        });

        // 8 * 50 = 400 attempts of 10 against a balance of 1000: exactly 100 can succeed
        assertThat(created.get()).isEqualTo(100);
        Account after = reload(guarded);
        assertThat(after.debitsPosted()).isEqualTo(1000);
        assertThat(after.debitsPosted()).isLessThanOrEqualTo(after.creditsPosted());
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void opposingBatchesOverTheSameAccountsDoNotDeadlock() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        Account c = newAccount();

        runThreads(THREADS, t -> {
            for (int i = 0; i < 25; i++) {
                // alternate the order of the accounts in the chain so naive locking would deadlock
                List<Transfer> chain = t % 2 == 0
                        ? List.of(linked(single(a, b, 1)), single(b, c, 1))
                        : List.of(linked(single(c, b, 1)), single(b, a, 1));
                transferService.submitBatch(chain);
            }
        });

        assertThat(count("transfers")).isEqualTo((long) THREADS * 25 * 2);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void concurrentPendingAndPostsKeepCountersConsistent() throws Exception {
        Account a = newAccount();
        Account b = newAccount();

        runThreads(THREADS, t -> {
            for (int i = 0; i < 25; i++) {
                TransferService.ItemResult p = transferService.submit(
                        Transfer.request(UUID.randomUUID(), a.id(), b.id(), 10, USD, 1, TransferFlags.PENDING, null,
                                0, null));
                if (i % 3 == 0) {
                    transferService.submit(voidOf(p.transfer().id()));
                } else {
                    transferService.submit(postPending(p.transfer().id(), 4));
                }
            }
        });

        long posts = THREADS * (25 - 9);      // i % 3 != 0 for 16 of 25 iterations
        Account after = reload(a);
        assertThat(after.debitsPending()).isZero();
        assertThat(after.debitsPosted()).isEqualTo(posts * 4);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface ThreadBody {
        void run(int threadIndex) throws Exception;
    }

    private static void runThreads(int threads, ThreadBody body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int index = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    body.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(180, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
