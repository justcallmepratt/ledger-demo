package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.app.TransferService.ItemResult;
import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferResult;
import dev.justcallmepratt.ledger.jobs.PendingExpiryJob;
import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import dev.justcallmepratt.ledger.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TwoPhaseIntegrationTest extends IntegrationTestBase {

    @Autowired PendingExpiryJob expiryJob;

    @Test
    void pendingReservesThenPostMovesTheFullAmount() {
        Account a = newAccount();
        Account b = newAccount();

        ItemResult pending = transferService.submit(pending(a, b, 300, 60));

        assertThat(pending.code()).isEqualTo(TransferResult.CREATED);
        assertThat(pending.transfer().pendingStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(pending.transfer().expiresAt()).isEqualTo(TestClockConfig.START.plusSeconds(60));
        assertThat(reload(a).debitsPending()).isEqualTo(300);
        assertThat(reload(b).creditsPending()).isEqualTo(300);
        assertThat(reload(a).debitsPosted()).isZero();

        ItemResult post = transferService.submit(post(pending.transfer().id(), 0));

        assertThat(post.code()).isEqualTo(TransferResult.CREATED);
        assertThat(post.transfer().amount()).isEqualTo(300);
        assertThat(post.transfer().debitAccountId()).isEqualTo(a.id());
        assertThat(reload(a).debitsPending()).isZero();
        assertThat(reload(a).debitsPosted()).isEqualTo(300);
        assertThat(reload(b).creditsPosted()).isEqualTo(300);
        assertThat(transferService.get(pending.transfer().id()).pendingStatus()).isEqualTo(PendingStatus.POSTED);
    }

    @Test
    void partialPostReleasesTheRemainder() {
        Account a = newAccount();
        Account b = newAccount();
        ItemResult pending = transferService.submit(pending(a, b, 300, 0));

        ItemResult post = transferService.submit(post(pending.transfer().id(), 120));

        assertThat(post.code()).isEqualTo(TransferResult.CREATED);
        assertThat(reload(a).debitsPending()).isZero();
        assertThat(reload(a).debitsPosted()).isEqualTo(120);
        assertThat(reload(b).creditsPending()).isZero();
        assertThat(reload(b).creditsPosted()).isEqualTo(120);
    }

    @Test
    void voidReleasesAndBlocksFurtherResolution() {
        Account a = newAccount();
        Account b = newAccount();
        ItemResult pending = transferService.submit(pending(a, b, 300, 0));

        assertThat(transferService.submit(voidOf(pending.transfer().id())).code()).isEqualTo(TransferResult.CREATED);

        assertThat(reload(a).debitsPending()).isZero();
        assertThat(reload(a).debitsPosted()).isZero();
        assertThat(transferService.get(pending.transfer().id()).pendingStatus()).isEqualTo(PendingStatus.VOIDED);
        assertThat(transferService.submit(post(pending.transfer().id(), 0)).code())
                .isEqualTo(TransferResult.PENDING_TRANSFER_ALREADY_VOIDED);
        assertThat(transferService.submit(voidOf(pending.transfer().id())).code())
                .isEqualTo(TransferResult.PENDING_TRANSFER_ALREADY_VOIDED);
    }

    @Test
    void aPendingTransferResolvesAtMostOnce() {
        Account a = newAccount();
        Account b = newAccount();
        ItemResult pending = transferService.submit(pending(a, b, 300, 0));

        assertThat(transferService.submit(post(pending.transfer().id(), 0)).code()).isEqualTo(TransferResult.CREATED);
        assertThat(transferService.submit(post(pending.transfer().id(), 0)).code())
                .isEqualTo(TransferResult.PENDING_TRANSFER_ALREADY_POSTED);
        assertThat(transferService.submit(voidOf(pending.transfer().id())).code())
                .isEqualTo(TransferResult.PENDING_TRANSFER_ALREADY_POSTED);
        assertThat(reload(a).debitsPosted()).isEqualTo(300);
    }

    @Test
    void postingMoreThanPendingIsRejected() {
        Account a = newAccount();
        Account b = newAccount();
        ItemResult pending = transferService.submit(pending(a, b, 300, 0));

        assertThat(transferService.submit(post(pending.transfer().id(), 301)).code())
                .isEqualTo(TransferResult.EXCEEDS_PENDING_TRANSFER_AMOUNT);
        assertThat(reload(a).debitsPending()).isEqualTo(300);
    }

    @Test
    void anExpiredPendingTransferCannotBePostedOrVoidedAndTheJobReleasesIt() {
        Account a = newAccount();
        Account b = newAccount();
        ItemResult pending = transferService.submit(pending(a, b, 300, 30));

        clock.advance(Duration.ofSeconds(31));

        assertThat(transferService.submit(post(pending.transfer().id(), 0)).code())
                .isEqualTo(TransferResult.PENDING_TRANSFER_EXPIRED);
        assertThat(transferService.submit(voidOf(pending.transfer().id())).code())
                .isEqualTo(TransferResult.PENDING_TRANSFER_EXPIRED);
        assertThat(reload(a).debitsPending()).isEqualTo(300);   // still held until the job runs
        assertThat(transferService.dueForExpiry()).containsExactly(pending.transfer().id());

        assertThat(expiryJob.expireDue()).isEqualTo(1);

        assertThat(reload(a).debitsPending()).isZero();
        assertThat(reload(b).creditsPending()).isZero();
        assertThat(transferService.get(pending.transfer().id()).pendingStatus()).isEqualTo(PendingStatus.EXPIRED);
        assertThat(transferService.submit(post(pending.transfer().id(), 0)).code())
                .isEqualTo(TransferResult.PENDING_TRANSFER_EXPIRED);
        assertThat(expiryJob.expireDue()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = "
                + "'dev.justcallmepratt.ledger.transfer.expired.v1'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void pendingTransfersWithoutTimeoutNeverExpire() {
        Account a = newAccount();
        Account b = newAccount();
        transferService.submit(pending(a, b, 300, 0));

        clock.advance(Duration.ofDays(365));

        assertThat(expiryJob.expireDue()).isZero();
        assertThat(reload(a).debitsPending()).isEqualTo(300);
    }

    @Test
    void expiringAnAlreadyResolvedTransferDoesNothing() {
        Account a = newAccount();
        Account b = newAccount();
        ItemResult pending = transferService.submit(pending(a, b, 300, 30));
        transferService.submit(post(pending.transfer().id(), 0));
        clock.advance(Duration.ofSeconds(60));

        assertThat(transferService.expire(pending.transfer().id())).isFalse();
        assertThat(transferService.expire(UUID.randomUUID())).isFalse();
        assertThat(reload(a).debitsPosted()).isEqualTo(300);
    }

    @Test
    void pendingListFiltersByStatusNewestFirst() {
        Account a = newAccount();
        Account b = newAccount();
        ItemResult p1 = transferService.submit(pending(a, b, 1, 0));
        ItemResult p2 = transferService.submit(pending(a, b, 2, 0));
        ItemResult p3 = transferService.submit(pending(a, b, 3, 0));
        transferService.submit(post(p2.transfer().id(), 0));
        transferService.submit(voidOf(p3.transfer().id()));

        assertThat(transferService.list(PendingStatus.PENDING, 50, null).items()).extracting(Transfer::id)
                .containsExactly(p1.transfer().id());
        assertThat(transferService.list(PendingStatus.POSTED, 50, null).items()).extracting(Transfer::id)
                .containsExactly(p2.transfer().id());
        assertThat(transferService.list(PendingStatus.VOIDED, 50, null).items()).extracting(Transfer::id)
                .containsExactly(p3.transfer().id());
        assertThat(transferService.list(PendingStatus.EXPIRED, 50, null).items()).isEmpty();
    }

    @Test
    void concurrentPostsOfTheSamePendingTransferHaveOneWinner() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        ItemResult pending = transferService.submit(pending(a, b, 300, 0));

        List<Callable<ItemResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> transferService.submit(post(pending.transfer().id(), 0)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        List<ItemResult> results = new ArrayList<>();
        try {
            List<Future<ItemResult>> futures = new ArrayList<>();
            for (Callable<ItemResult> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<ItemResult> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results.stream().filter(r -> r.code() == TransferResult.CREATED).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.code() == TransferResult.PENDING_TRANSFER_ALREADY_POSTED).count())
                .isEqualTo(5);
        assertThat(reload(a).debitsPosted()).isEqualTo(300);
        assertThat(reload(a).debitsPending()).isZero();
    }

    @Test
    void pendingCountsAgainstLimitsUntilReleased() {
        Account limited = newAccount(new AccountFlags(true, false, false, false));
        Account sink = newAccount();
        fund(limited, 100);

        ItemResult hold = transferService.submit(pending(limited, sink, 80, 0));
        assertThat(transferService.submit(single(limited, sink, 30)).code())
                .isEqualTo(TransferResult.EXCEEDS_CREDITS);

        transferService.submit(voidOf(hold.transfer().id()));
        assertThat(transferService.submit(single(limited, sink, 30)).code()).isEqualTo(TransferResult.CREATED);
    }
}
