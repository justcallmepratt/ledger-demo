package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.app.TransferService.ItemResult;
import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferResult;
import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LinkedChainIntegrationTest extends IntegrationTestBase {

    private static List<TransferResult> codes(List<ItemResult> results) {
        return results.stream().map(ItemResult::code).toList();
    }

    @Test
    void aFailingItemRollsBackTheWholeChain() {
        Account rich = newAccount();
        Account limited = newAccount(new AccountFlags(true, false, false, false));
        Account sink = newAccount();
        fund(limited, 100);
        long outboxBefore = count("outbox");
        long transfersBefore = count("transfers");

        List<ItemResult> results = transferService.submitBatch(List.of(
                linked(single(rich, sink, 40)),
                linked(single(limited, sink, 60)),
                single(limited, sink, 60)));   // limited only has 100: 60 + 60 exceeds

        assertThat(codes(results)).containsExactly(TransferResult.LINKED_EVENT_FAILED,
                TransferResult.LINKED_EVENT_FAILED, TransferResult.EXCEEDS_CREDITS);
        assertThat(results.get(0).transfer()).isNull();
        assertThat(reload(rich).debitsPosted()).isZero();
        assertThat(reload(limited).debitsPosted()).isZero();
        assertThat(reload(sink).creditsPosted()).isZero();
        assertThat(count("transfers")).isEqualTo(transfersBefore);
        assertThat(count("outbox")).isEqualTo(outboxBefore);
    }

    @Test
    void aSuccessfulChainCommitsAtomically() {
        Account a = newAccount();
        Account b = newAccount();
        Account c = newAccount();

        List<ItemResult> results = transferService.submitBatch(List.of(
                linked(single(a, b, 10)),
                single(b, c, 10)));

        assertThat(codes(results)).containsExactly(TransferResult.CREATED, TransferResult.CREATED);
        assertThat(reload(a).debitsPosted()).isEqualTo(10);
        assertThat(reload(b).debitsPosted()).isEqualTo(10);
        assertThat(reload(b).creditsPosted()).isEqualTo(10);
        assertThat(reload(c).creditsPosted()).isEqualTo(10);
        assertThat(results.get(0).transfer().flags().linked()).isTrue();
        assertThat(results.get(1).index()).isEqualTo(1);
    }

    @Test
    void chainsAreIndependentOfEachOther() {
        Account a = newAccount();
        Account b = newAccount();

        List<ItemResult> results = transferService.submitBatch(List.of(
                single(a, b, 5),                                   // chain 1: ok
                linked(single(a, b, 0)),                           // chain 2: invalid amount
                single(a, b, 7),                                   // chain 2 end
                single(a, b, 9)));                                 // chain 3: ok

        assertThat(codes(results)).containsExactly(TransferResult.CREATED, TransferResult.AMOUNT_MUST_BE_POSITIVE,
                TransferResult.LINKED_EVENT_FAILED, TransferResult.CREATED);
        assertThat(reload(a).debitsPosted()).isEqualTo(14);
        assertThat(count("transfers")).isEqualTo(2);
    }

    @Test
    void aBatchEndingInsideAChainFailsThatChain() {
        Account a = newAccount();
        Account b = newAccount();

        List<ItemResult> results = transferService.submitBatch(List.of(
                single(a, b, 1),
                linked(single(a, b, 2)),
                linked(single(a, b, 3))));

        assertThat(codes(results)).containsExactly(TransferResult.CREATED, TransferResult.LINKED_EVENT_FAILED,
                TransferResult.LINKED_EVENT_CHAIN_OPEN);
        assertThat(reload(a).debitsPosted()).isEqualTo(1);
    }

    @Test
    void replayingACommittedChainIsANoOp() {
        Account a = newAccount();
        Account b = newAccount();
        List<Transfer> batch = List.of(linked(single(a, b, 10)), single(b, a, 4));

        List<ItemResult> first = transferService.submitBatch(batch);
        List<ItemResult> replay = transferService.submitBatch(batch);

        assertThat(codes(first)).containsOnly(TransferResult.CREATED);
        assertThat(codes(replay)).containsOnly(TransferResult.EXISTS);
        assertThat(replay.get(0).transfer().seq()).isEqualTo(first.get(0).transfer().seq());
        assertThat(count("transfers")).isEqualTo(2);
        assertThat(reload(a).debitsPosted()).isEqualTo(10);
    }

    @Test
    void aPartiallyExistingChainFailsWithTheExistsCode() {
        Account a = newAccount();
        Account b = newAccount();
        Transfer existing = linked(single(a, b, 10));
        transferService.submitBatch(List.of(existing, single(a, b, 1)));

        List<ItemResult> results = transferService.submitBatch(List.of(existing, single(a, b, 20)));

        assertThat(codes(results)).containsExactly(TransferResult.EXISTS, TransferResult.LINKED_EVENT_FAILED);
        assertThat(reload(a).debitsPosted()).isEqualTo(11);
        assertThat(count("transfers")).isEqualTo(2);
    }

    @Test
    void aChainCanPostAPendingTransferCreatedEarlierInTheSameChain() {
        Account a = newAccount();
        Account b = newAccount();
        Transfer pending = linked(pending(a, b, 80, 0));
        Transfer post = post(pending.id(), 50);

        List<ItemResult> results = transferService.submitBatch(List.of(pending, post));

        assertThat(codes(results)).containsExactly(TransferResult.CREATED, TransferResult.CREATED);
        assertThat(reload(a).debitsPending()).isZero();
        assertThat(reload(a).debitsPosted()).isEqualTo(50);
        assertThat(transferService.get(pending.id()).pendingStatus().name()).isEqualTo("POSTED");
    }

    @Test
    void singleSubmitRejectsLinked() {
        Account a = newAccount();
        Account b = newAccount();

        ItemResult result = transferService.submit(linked(single(a, b, 1)));

        assertThat(result.code()).isEqualTo(TransferResult.LINKED_NOT_ALLOWED);
        assertThat(count("transfers")).isZero();
    }

    @Test
    void rejectionsReportTheirCodesAndWriteNothing() {
        Account a = newAccount();
        Account b = newAccount();
        Transfer missing = Transfer.request(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), b.id(), 5, 840,
                1, dev.justcallmepratt.ledger.domain.TransferFlags.NONE, null, 0, null);

        assertThat(transferService.submit(missing).code()).isEqualTo(TransferResult.DEBIT_ACCOUNT_NOT_FOUND);
        assertThat(transferService.submit(post(java.util.UUID.randomUUID(), 0)).code())
                .isEqualTo(TransferResult.PENDING_TRANSFER_NOT_FOUND);
        assertThat(count("transfers")).isZero();
        assertThat(reload(a).debitsPosted()).isZero();
    }
}
