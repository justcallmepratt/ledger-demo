package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferResult;
import dev.justcallmepratt.ledger.persistence.BalanceHistoryEntry;
import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitsHistoryPaginationIntegrationTest extends IntegrationTestBase {

    @Test
    void debitsMustNotExceedCredits() {
        Account wallet = newAccount(new AccountFlags(true, false, false, false));
        Account other = newAccount();
        fund(wallet, 1000);

        assertThat(transferService.submit(single(wallet, other, 1001)).code()).isEqualTo(TransferResult.EXCEEDS_CREDITS);
        assertThat(transferService.submit(single(wallet, other, 1000)).code()).isEqualTo(TransferResult.CREATED);
        assertThat(transferService.submit(single(wallet, other, 1)).code()).isEqualTo(TransferResult.EXCEEDS_CREDITS);
        assertThat(reload(wallet).debitsPosted()).isEqualTo(1000);
    }

    @Test
    void creditsMustNotExceedDebits() {
        Account limited = newAccount(new AccountFlags(false, true, false, false));
        Account other = newAccount();

        assertThat(transferService.submit(single(other, limited, 1)).code()).isEqualTo(TransferResult.EXCEEDS_DEBITS);
        assertThat(transferService.submit(single(limited, other, 100)).code()).isEqualTo(TransferResult.CREATED);
        assertThat(transferService.submit(single(other, limited, 100)).code()).isEqualTo(TransferResult.CREATED);
        assertThat(transferService.submit(single(other, limited, 1)).code()).isEqualTo(TransferResult.EXCEEDS_DEBITS);
    }

    @Test
    void closedAccountsRejectTransfers() {
        Account a = newAccount();
        Account b = newAccount();
        jdbc.sql("UPDATE accounts SET closed = TRUE WHERE id = :id").param("id", b.id()).update();

        assertThat(transferService.submit(single(a, b, 5)).code()).isEqualTo(TransferResult.CREDIT_ACCOUNT_CLOSED);
        assertThat(transferService.submit(single(b, a, 5)).code()).isEqualTo(TransferResult.DEBIT_ACCOUNT_CLOSED);
    }

    @Test
    void mismatchedLedgersAreRejected() {
        Account a = newAccount();
        jdbc.sql("INSERT INTO ledgers (id, currency, asset_scale) VALUES (978, 'EUR', 2) ON CONFLICT DO NOTHING").update();
        Account eur = accountService.create(Account.open(UUID.randomUUID(), 978, 1, "eur", AccountFlags.NONE, null))
                .account();

        assertThat(transferService.submit(single(a, eur, 5)).code()).isEqualTo(TransferResult.LEDGER_MUST_MATCH);
        Transfer wrongLedger = Transfer.request(UUID.randomUUID(), a.id(), newAccount().id(), 5, 978, 1,
                dev.justcallmepratt.ledger.domain.TransferFlags.NONE, null, 0, null);
        assertThat(transferService.submit(wrongLedger).code())
                .isEqualTo(TransferResult.TRANSFER_MUST_HAVE_SAME_LEDGER_AS_ACCOUNTS);
    }

    @Test
    void historyRecordsTheBalanceAfterEachTransferNewestFirst() {
        Account tracked = newAccount(new AccountFlags(false, false, true, false));
        Account other = newAccount();
        for (int i = 1; i <= 5; i++) {
            transferService.submit(single(other, tracked, i * 10));
        }

        Page<BalanceHistoryEntry> first = accountService.balances(tracked.id(), 2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(first.items().get(0).creditsPosted()).isEqualTo(150);
        assertThat(first.items().get(1).creditsPosted()).isEqualTo(100);
        assertThat(first.items().get(0).seq()).isGreaterThan(first.items().get(1).seq());

        Page<BalanceHistoryEntry> second = accountService.balances(tracked.id(), 2, first.nextCursor());
        assertThat(second.items()).extracting(BalanceHistoryEntry::creditsPosted).containsExactly(60L, 30L);

        Page<BalanceHistoryEntry> third = accountService.balances(tracked.id(), 2, second.nextCursor());
        assertThat(third.items()).extracting(BalanceHistoryEntry::creditsPosted).containsExactly(10L);
        assertThat(third.nextCursor()).isNull();
        // the other account has no history flag: no rows for it
        assertThat(jdbc.sql("SELECT count(*) FROM account_balance_history WHERE account_id = :id")
                .param("id", other.id()).query(Long.class).single()).isZero();
    }

    @Test
    void historyIsRefusedForAccountsWithoutTheFlag() {
        Account plain = newAccount();

        assertThatThrownBy(() -> accountService.balances(plain.id(), 10, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("history_not_enabled");
                    assertThat(e.status().value()).isEqualTo(422);
                });
        assertThatThrownBy(() -> accountService.balances(UUID.randomUUID(), 10, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("account_not_found"));
    }

    @Test
    void accountTransfersPageNewestFirstWithoutGapsOrDuplicates() {
        Account a = newAccount();
        Account b = newAccount();
        Account c = newAccount();
        List<UUID> expectedNewestFirst = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Transfer t = i % 2 == 0 ? single(a, b, i + 1) : single(c, a, i + 1);
            transferService.submit(t);
            expectedNewestFirst.add(0, t.id());
        }
        transferService.submit(single(b, c, 99));   // does not touch a

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Page<Transfer> page = accountService.transfers(a.id(), 3, cursor);
            page.items().forEach(t -> seen.add(t.id()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expectedNewestFirst);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void transferListPagesByKeyset() {
        Account a = newAccount();
        Account b = newAccount();
        for (int i = 0; i < 5; i++) {
            transferService.submit(single(a, b, 1));
        }

        Page<Transfer> first = transferService.list(null, 2, null);
        Page<Transfer> second = transferService.list(null, 2, first.nextCursor());
        Page<Transfer> third = transferService.list(null, 2, second.nextCursor());

        assertThat(first.items()).hasSize(2);
        assertThat(third.items()).hasSize(1);
        assertThat(third.nextCursor()).isNull();
        List<Long> seqs = new ArrayList<>();
        first.items().forEach(t -> seqs.add(t.seq()));
        second.items().forEach(t -> seqs.add(t.seq()));
        third.items().forEach(t -> seqs.add(t.seq()));
        assertThat(seqs).isSortedAccordingTo(java.util.Comparator.reverseOrder()).doesNotHaveDuplicates();
    }

    @Test
    void accountListPagesNewestFirstWithTieBreakById() {
        List<UUID> expected = new ArrayList<>();
        // three accounts share one timestamp, then two more are created later
        List<Account> sameInstant = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sameInstant.add(newAccount());
        }
        clock.advance(Duration.ofSeconds(1));
        Account later1 = newAccount();
        clock.advance(Duration.ofSeconds(1));
        Account later2 = newAccount();
        expected.add(later2.id());
        expected.add(later1.id());
        sameInstant.stream().map(Account::id).sorted(java.util.Comparator.reverseOrder()).forEach(expected::add);

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        do {
            Page<Account> page = accountService.list(2, cursor);
            page.items().forEach(a -> seen.add(a.id()));
            cursor = page.nextCursor();
        } while (cursor != null);

        // PostgreSQL orders uuid bytewise, java.util.UUID compares signed longs: compare as sets per tier
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(seen.subList(0, 2)).containsExactly(later2.id(), later1.id());
        assertThat(seen.subList(2, 5)).containsExactlyInAnyOrderElementsOf(
                sameInstant.stream().map(Account::id).toList());
    }

    @Test
    void invalidLimitsAndCursorsAreBadRequests() {
        assertThatThrownBy(() -> accountService.list(0, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> accountService.list(201, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> accountService.list(10, "!!not-base64!!")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> transferService.list(null, 10, "bm9wZQ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> transferService.get(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("transfer_not_found"));
    }
}
