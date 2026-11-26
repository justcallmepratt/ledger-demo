package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.app.AccountService.CreateResult;
import dev.justcallmepratt.ledger.app.TransferService.ItemResult;
import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import dev.justcallmepratt.ledger.domain.TransferResult;
import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferIdempotencyIntegrationTest extends IntegrationTestBase {

    @Test
    void identicalReplayReturnsTheStoredTransferAndChangesNothing() {
        Account a = newAccount();
        Account b = newAccount();
        Transfer request = single(a, b, 500);

        ItemResult first = transferService.submit(request);
        ItemResult second = transferService.submit(request);

        assertThat(first.code()).isEqualTo(TransferResult.CREATED);
        assertThat(second.code()).isEqualTo(TransferResult.EXISTS);
        assertThat(second.transfer().seq()).isEqualTo(first.transfer().seq());
        assertThat(reload(a).debitsPosted()).isEqualTo(500);
        assertThat(reload(b).creditsPosted()).isEqualTo(500);
        assertThat(count("transfers")).isEqualTo(1);
        // one transfer event, no duplicate from the replay
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'ledger.transfers.v1'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void sameIdWithDifferentFieldsIsRejectedAndNamesTheFields() {
        Account a = newAccount();
        Account b = newAccount();
        Transfer request = single(a, b, 500);
        transferService.submit(request);

        Transfer changed = Transfer.request(request.id(), a.id(), b.id(), 501, 840, 2, TransferFlags.NONE, null, 0,
                "other");
        ItemResult result = transferService.submit(changed);

        assertThat(result.code()).isEqualTo(TransferResult.EXISTS_WITH_DIFFERENT_FIELDS);
        assertThat(result.detail()).contains("amount").contains("code").contains("reference");
        assertThat(reload(a).debitsPosted()).isEqualTo(500);
    }

    @Test
    void concurrentDuplicateIdsApplyExactlyOnce() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        Transfer request = single(a, b, 100);
        int threads = 8;

        List<ItemResult> results = runConcurrently(threads, () -> transferService.submit(request));

        long created = results.stream().filter(r -> r.code() == TransferResult.CREATED).count();
        long exists = results.stream().filter(r -> r.code() == TransferResult.EXISTS).count();
        assertThat(created).isEqualTo(1);
        assertThat(exists).isEqualTo(threads - 1);
        assertThat(reload(a).debitsPosted()).isEqualTo(100);
        assertThat(count("transfers")).isEqualTo(1);
    }

    @Test
    void concurrentSameIdWithDifferentBodiesHasOneWinner() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        Account c = newAccount();
        UUID id = UUID.randomUUID();
        Transfer toB = Transfer.request(id, a.id(), b.id(), 10, 840, 1, TransferFlags.NONE, null, 0, null);
        Transfer toC = Transfer.request(id, a.id(), c.id(), 10, 840, 1, TransferFlags.NONE, null, 0, null);

        List<Callable<ItemResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Transfer t = i % 2 == 0 ? toB : toC;
            tasks.add(() -> transferService.submit(t));
        }
        List<ItemResult> results = runAll(tasks);

        assertThat(results.stream().filter(r -> r.code() == TransferResult.CREATED).count()).isEqualTo(1);
        assertThat(results).allSatisfy(r -> assertThat(r.code()).isIn(TransferResult.CREATED,
                TransferResult.EXISTS, TransferResult.EXISTS_WITH_DIFFERENT_FIELDS));
        assertThat(count("transfers")).isEqualTo(1);
        assertThat(reload(a).debitsPosted()).isEqualTo(10);
    }

    @Test
    void postReplayedWithOmittedFieldsStillMatches() {
        Account a = newAccount();
        Account b = newAccount();
        ItemResult p = transferService.submit(pending(a, b, 300, 0));
        Transfer post = postPending(p.transfer().id(), 0);

        assertThat(transferService.submit(post).code()).isEqualTo(TransferResult.CREATED);
        assertThat(transferService.submit(post).code()).isEqualTo(TransferResult.EXISTS);
        // asking for a partial amount under the same id is a different request
        Transfer partial = Transfer.request(post.id(), null, null, 100, 0, 0, TransferFlags.POST, p.transfer().id(),
                0, null);
        assertThat(transferService.submit(partial).code()).isEqualTo(TransferResult.EXISTS_WITH_DIFFERENT_FIELDS);
    }

    @Test
    void accountCreateIsIdempotent() {
        UUID id = UUID.randomUUID();
        Account requested = Account.open(id, 840, 7, "Alice wallet", new AccountFlags(true, false, true, false), null);

        CreateResult first = accountService.create(requested);
        CreateResult second = accountService.create(requested);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.account()).isEqualTo(first.account());
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'ledger.accounts.v1'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void accountWithSameIdAndDifferentFieldsConflicts() {
        UUID id = UUID.randomUUID();
        accountService.create(Account.open(id, 840, 7, "Alice wallet", AccountFlags.NONE, null));

        assertThatThrownBy(() -> accountService.create(
                Account.open(id, 840, 8, "Alice wallet 2", AccountFlags.NONE, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("exists_with_different_fields");
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("code").contains("name");
                });
    }

    @Test
    void accountWithUnknownLedgerIsRejected() {
        assertThatThrownBy(() -> accountService.create(
                Account.open(UUID.randomUUID(), 999, 1, "x", AccountFlags.NONE, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("ledger_not_found"));
    }

    @Test
    void concurrentAccountCreatesYieldOneAccount() throws Exception {
        UUID id = UUID.randomUUID();
        Account requested = Account.open(id, 840, 1, "race", AccountFlags.NONE, null);

        List<CreateResult> results = runConcurrently(8, () -> accountService.create(requested));

        assertThat(results.stream().filter(CreateResult::created).count()).isEqualTo(1);
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return runAll(tasks);
    }

    private static <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
