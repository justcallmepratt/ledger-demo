package dev.justcallmepratt.ledger.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainRecordsTest {

    private static final Instant T = Instant.parse("2026-11-26T08:00:00Z");

    @Test
    void accountRejectsNegativeCounters() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new Account(id, 840, 1, "x", AccountFlags.NONE, -1, 0, 0, 0, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Account(id, 840, 1, "x", AccountFlags.NONE, 0, -1, 0, 0, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Account(id, 840, 1, "x", AccountFlags.NONE, 0, 0, -1, 0, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Account(id, 840, 1, "x", AccountFlags.NONE, 0, 0, 0, -1, T))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountRequiresIdNameAndFlags() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> Account.open(null, 840, 1, "x", AccountFlags.NONE, T))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Account.open(id, 840, 1, null, AccountFlags.NONE, T))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Account.open(id, 840, 1, "x", null, T)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void withCountersReplacesOnlyCounters() {
        Account a = Account.open(UUID.randomUUID(), 840, 7, "n", AccountFlags.NONE, T).withCounters(1, 2, 3, 4);
        assertThat(a.debitsPending()).isEqualTo(1);
        assertThat(a.debitsPosted()).isEqualTo(2);
        assertThat(a.creditsPending()).isEqualTo(3);
        assertThat(a.creditsPosted()).isEqualTo(4);
        assertThat(a.code()).isEqualTo(7);
        assertThat(a.name()).isEqualTo("n");
    }

    @Test
    void transferRejectsNegativeAmountAndTimeout() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> Transfer.request(id, id, id, -1, 840, 1, TransferFlags.NONE, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transfer.request(id, id, id, 1, 840, 1, TransferFlags.NONE, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transfer.request(id, id, id, 1, 840, 1, null, null, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void transferCopyMethods() {
        UUID id = UUID.randomUUID();
        Transfer t = Transfer.request(id, id, id, 1, 840, 1, TransferFlags.PENDING, null, 0, "r");
        Transfer stored = t.withStored(9, T).withPendingStatus(PendingStatus.POSTED);
        assertThat(stored.seq()).isEqualTo(9);
        assertThat(stored.timestamp()).isEqualTo(T);
        assertThat(stored.pendingStatus()).isEqualTo(PendingStatus.POSTED);
        assertThat(stored.reference()).isEqualTo("r");
        assertThat(t.seq()).isZero();
    }

    @Test
    void flagsHelpers() {
        assertThat(TransferFlags.NONE.withLinked(true).linked()).isTrue();
        assertThat(TransferFlags.POST.resolves()).isTrue();
        assertThat(TransferFlags.VOID.resolves()).isTrue();
        assertThat(TransferFlags.PENDING.resolves()).isFalse();
        assertThat(TransferFlags.PENDING.withLinked(true).pending()).isTrue();
    }

    @Test
    void resultCodesAreSnakeCaseAndCategorised() {
        assertThat(TransferResult.EXCEEDS_CREDITS.code()).isEqualTo("exceeds_credits");
        assertThat(TransferResult.EXISTS_WITH_DIFFERENT_FIELDS.code()).isEqualTo("exists_with_different_fields");
        assertThat(TransferResult.CREATED.isSuccess()).isTrue();
        assertThat(TransferResult.EXISTS.isSuccess()).isTrue();
        assertThat(TransferResult.EXCEEDS_DEBITS.isSuccess()).isFalse();
        assertThat(TransferResult.EXISTS_WITH_DIFFERENT_FIELDS.category())
                .isEqualTo(TransferResult.Category.CONFLICT);
        assertThat(TransferResult.LINKED_EVENT_FAILED.category()).isEqualTo(TransferResult.Category.BATCH_ONLY);
        assertThat(TransferResult.DEBIT_ACCOUNT_NOT_FOUND.category()).isEqualTo(TransferResult.Category.NOT_FOUND);
        assertThat(TransferResult.AMOUNT_MUST_BE_POSITIVE.category()).isEqualTo(TransferResult.Category.INVALID);
    }

    @Test
    void ledgerRecord() {
        Ledger l = new Ledger(840, "USD", 2);
        assertThat(l.currency()).isEqualTo("USD");
    }
}
