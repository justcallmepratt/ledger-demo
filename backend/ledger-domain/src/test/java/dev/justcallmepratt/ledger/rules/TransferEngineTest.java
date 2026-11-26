package dev.justcallmepratt.ledger.rules;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import dev.justcallmepratt.ledger.domain.TransferResult;
import dev.justcallmepratt.ledger.rules.TransferEngine.Applied;
import dev.justcallmepratt.ledger.rules.TransferEngine.Outcome;
import dev.justcallmepratt.ledger.rules.TransferEngine.Rejected;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static dev.justcallmepratt.ledger.rules.Fixtures.NOW;
import static dev.justcallmepratt.ledger.rules.Fixtures.USD;
import static dev.justcallmepratt.ledger.rules.Fixtures.account;
import static dev.justcallmepratt.ledger.rules.Fixtures.closed;
import static dev.justcallmepratt.ledger.rules.Fixtures.creditLimited;
import static dev.justcallmepratt.ledger.rules.Fixtures.debitLimited;
import static dev.justcallmepratt.ledger.rules.Fixtures.funded;
import static dev.justcallmepratt.ledger.rules.Fixtures.pending;
import static dev.justcallmepratt.ledger.rules.Fixtures.post;
import static dev.justcallmepratt.ledger.rules.Fixtures.single;
import static dev.justcallmepratt.ledger.rules.Fixtures.storedPending;
import static dev.justcallmepratt.ledger.rules.Fixtures.uuid;
import static dev.justcallmepratt.ledger.rules.Fixtures.voided;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferEngineTest {

    private final Account a = account(1);
    private final Account b = account(2);

    private static TransferResult code(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Rejected.class);
        return ((Rejected) outcome).code();
    }

    private static Applied applied(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Applied.class);
        return (Applied) outcome;
    }

    // ---- single phase ----

    @Test
    void singlePhaseMovesPostedCounters() {
        Applied r = applied(TransferEngine.apply(single(10, a, b, 500), a, b, null, NOW));
        assertThat(r.debit().debitsPosted()).isEqualTo(500);
        assertThat(r.credit().creditsPosted()).isEqualTo(500);
        assertThat(r.debit().debitsPending()).isZero();
        assertThat(r.transfer().timestamp()).isEqualTo(NOW);
        assertThat(r.transfer().pendingStatus()).isNull();
        assertThat(r.resolvedStatus()).isNull();
    }

    @Test
    void nilIdIsRejected() {
        Transfer t = Transfer.request(new UUID(0, 0), a.id(), b.id(), 1, USD, 1, TransferFlags.NONE, null, 0, null);
        assertThat(code(TransferEngine.apply(t, a, b, null, NOW))).isEqualTo(TransferResult.ID_MUST_NOT_BE_NIL);
    }

    @Test
    void nullIdIsRejected() {
        Transfer t = Transfer.request(null, a.id(), b.id(), 1, USD, 1, TransferFlags.NONE, null, 0, null);
        assertThat(code(TransferEngine.apply(t, a, b, null, NOW))).isEqualTo(TransferResult.ID_MUST_NOT_BE_NIL);
    }

    @Test
    void mutuallyExclusiveFlags() {
        Transfer t = Transfer.request(uuid(10), a.id(), b.id(), 1, USD, 1,
                new TransferFlags(false, true, true, false), uuid(9), 0, null);
        assertThat(code(TransferEngine.apply(t, a, b, null, NOW)))
                .isEqualTo(TransferResult.FLAGS_ARE_MUTUALLY_EXCLUSIVE);
    }

    @Test
    void postWithoutPendingIdIsRejected() {
        Transfer t = Transfer.request(uuid(10), null, null, 0, 0, 0, TransferFlags.POST, null, 0, null);
        assertThat(code(TransferEngine.apply(t, null, null, null, NOW))).isEqualTo(TransferResult.PENDING_ID_REQUIRED);
    }

    @Test
    void nilPendingIdIsRejectedForVoid() {
        Transfer t = Transfer.request(uuid(10), null, null, 0, 0, 0, TransferFlags.VOID, new UUID(0, 0), 0, null);
        assertThat(code(TransferEngine.apply(t, null, null, null, NOW))).isEqualTo(TransferResult.PENDING_ID_REQUIRED);
    }

    @Test
    void pendingIdOnPlainTransferIsRejected() {
        Transfer t = Transfer.request(uuid(10), a.id(), b.id(), 1, USD, 1, TransferFlags.NONE, uuid(3), 0, null);
        assertThat(code(TransferEngine.apply(t, a, b, null, NOW))).isEqualTo(TransferResult.PENDING_ID_MUST_BE_NULL);
    }

    @Test
    void timeoutOnlyAllowedForPending() {
        Transfer t = Transfer.request(uuid(10), a.id(), b.id(), 1, USD, 1, TransferFlags.NONE, null, 30, null);
        assertThat(code(TransferEngine.apply(t, a, b, null, NOW)))
                .isEqualTo(TransferResult.TIMEOUT_RESERVED_FOR_PENDING);
    }

    @Test
    void missingDebitAccount() {
        assertThat(code(TransferEngine.apply(single(10, a, b, 1), null, b, null, NOW)))
                .isEqualTo(TransferResult.DEBIT_ACCOUNT_NOT_FOUND);
    }

    @Test
    void missingCreditAccount() {
        assertThat(code(TransferEngine.apply(single(10, a, b, 1), a, null, null, NOW)))
                .isEqualTo(TransferResult.CREDIT_ACCOUNT_NOT_FOUND);
    }

    @Test
    void snapshotsMustMatchRequestIds() {
        assertThatThrownBy(() -> TransferEngine.apply(single(10, a, b, 1), b, a, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountsMustDiffer() {
        Transfer t = Transfer.request(uuid(10), a.id(), a.id(), 1, USD, 1, TransferFlags.NONE, null, 0, null);
        assertThat(code(TransferEngine.apply(t, a, a, null, NOW))).isEqualTo(TransferResult.ACCOUNTS_MUST_BE_DIFFERENT);
    }

    @Test
    void accountsMustShareLedger() {
        Account eur = account(2, 978, AccountFlags.NONE);
        assertThat(code(TransferEngine.apply(single(10, a, eur, 1), a, eur, null, NOW)))
                .isEqualTo(TransferResult.LEDGER_MUST_MATCH);
    }

    @Test
    void transferLedgerMustMatchAccounts() {
        Transfer t = Transfer.request(uuid(10), a.id(), b.id(), 1, 978, 1, TransferFlags.NONE, null, 0, null);
        assertThat(code(TransferEngine.apply(t, a, b, null, NOW)))
                .isEqualTo(TransferResult.TRANSFER_MUST_HAVE_SAME_LEDGER_AS_ACCOUNTS);
    }

    @Test
    void amountMustBePositive() {
        assertThat(code(TransferEngine.apply(single(10, a, b, 0), a, b, null, NOW)))
                .isEqualTo(TransferResult.AMOUNT_MUST_BE_POSITIVE);
    }

    @Test
    void closedDebitAccount() {
        Account c = account(1, USD, closed());
        assertThat(code(TransferEngine.apply(single(10, c, b, 1), c, b, null, NOW)))
                .isEqualTo(TransferResult.DEBIT_ACCOUNT_CLOSED);
    }

    @Test
    void closedCreditAccount() {
        Account c = account(2, USD, closed());
        assertThat(code(TransferEngine.apply(single(10, a, c, 1), a, c, null, NOW)))
                .isEqualTo(TransferResult.CREDIT_ACCOUNT_CLOSED);
    }

    @Test
    void debitLimitExceeded() {
        Account d = funded(1, debitLimited(), 0, 0, 0, 100);
        assertThat(code(TransferEngine.apply(single(10, d, b, 101), d, b, null, NOW)))
                .isEqualTo(TransferResult.EXCEEDS_CREDITS);
    }

    @Test
    void debitLimitAllowsExactBalance() {
        Account d = funded(1, debitLimited(), 0, 0, 0, 100);
        Applied r = applied(TransferEngine.apply(single(10, d, b, 100), d, b, null, NOW));
        assertThat(r.debit().debitsPosted()).isEqualTo(100);
    }

    @Test
    void pendingDebitsCountAgainstTheLimit() {
        Account d = funded(1, debitLimited(), 60, 0, 0, 100);
        assertThat(code(TransferEngine.apply(single(10, d, b, 41), d, b, null, NOW)))
                .isEqualTo(TransferResult.EXCEEDS_CREDITS);
    }

    @Test
    void creditLimitExceeded() {
        Account c = funded(2, creditLimited(), 0, 100, 0, 0);
        assertThat(code(TransferEngine.apply(single(10, a, c, 101), a, c, null, NOW)))
                .isEqualTo(TransferResult.EXCEEDS_DEBITS);
    }

    @Test
    void limitedAccountWithOverflowingSumIsExceeded() {
        Account d = funded(1, debitLimited(), 0, Long.MAX_VALUE, 0, Long.MAX_VALUE);
        assertThat(code(TransferEngine.apply(single(10, d, b, 1), d, b, null, NOW)))
                .isEqualTo(TransferResult.EXCEEDS_CREDITS);
    }

    @Test
    void overflowOnDebitSide() {
        Account d = funded(1, AccountFlags.NONE, 0, Long.MAX_VALUE, 0, 0);
        assertThat(code(TransferEngine.apply(single(10, d, b, 1), d, b, null, NOW)))
                .isEqualTo(TransferResult.OVERFLOWS_BALANCE);
    }

    @Test
    void overflowOnCreditSide() {
        Account c = funded(2, AccountFlags.NONE, 0, 0, 0, Long.MAX_VALUE);
        assertThat(code(TransferEngine.apply(single(10, a, c, 1), a, c, null, NOW)))
                .isEqualTo(TransferResult.OVERFLOWS_BALANCE);
    }

    @Test
    void overflowThroughPendingPlusPostedSum() {
        Account d = funded(1, AccountFlags.NONE, Long.MAX_VALUE - 5, 10, 0, 0);
        assertThat(code(TransferEngine.apply(single(10, d, b, 1), d, b, null, NOW)))
                .isEqualTo(TransferResult.OVERFLOWS_BALANCE);
    }

    @Test
    void validationOrderFirstFailureWins() {
        // zero amount AND closed account: amount is checked first
        Account c = account(1, USD, closed());
        assertThat(code(TransferEngine.apply(single(10, c, b, 0), c, b, null, NOW)))
                .isEqualTo(TransferResult.AMOUNT_MUST_BE_POSITIVE);
        // closed AND over limit: closed is checked first
        Account both = funded(1, new AccountFlags(true, false, false, true), 0, 0, 0, 0);
        assertThat(code(TransferEngine.apply(single(10, both, b, 5), both, b, null, NOW)))
                .isEqualTo(TransferResult.DEBIT_ACCOUNT_CLOSED);
    }

    // ---- pending ----

    @Test
    void pendingAddsToPendingCountersOnly() {
        Applied r = applied(TransferEngine.apply(pending(10, a, b, 300, 60), a, b, null, NOW));
        assertThat(r.debit().debitsPending()).isEqualTo(300);
        assertThat(r.credit().creditsPending()).isEqualTo(300);
        assertThat(r.debit().debitsPosted()).isZero();
        assertThat(r.transfer().pendingStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(r.transfer().expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void pendingWithoutTimeoutNeverExpires() {
        Applied r = applied(TransferEngine.apply(pending(10, a, b, 300, 0), a, b, null, NOW));
        assertThat(r.transfer().expiresAt()).isNull();
    }

    @Test
    void pendingOverflowDetected() {
        Account d = funded(1, AccountFlags.NONE, Long.MAX_VALUE, 0, 0, 0);
        assertThat(code(TransferEngine.apply(pending(10, d, b, 1, 0), d, b, null, NOW)))
                .isEqualTo(TransferResult.OVERFLOWS_BALANCE);
    }

    // ---- post / void ----

    private final Account da = funded(1, AccountFlags.NONE, 300, 0, 0, 0);
    private final Account cb = funded(2, AccountFlags.NONE, 0, 0, 300, 0);

    private Transfer open(Instant expiresAt) {
        return storedPending(5, a, b, 300, PendingStatus.PENDING, expiresAt);
    }

    @Test
    void postFullAmountWhenAmountOmitted() {
        Applied r = applied(TransferEngine.apply(post(11, 5, 0), da, cb, open(null), NOW));
        assertThat(r.debit().debitsPending()).isZero();
        assertThat(r.debit().debitsPosted()).isEqualTo(300);
        assertThat(r.credit().creditsPending()).isZero();
        assertThat(r.credit().creditsPosted()).isEqualTo(300);
        assertThat(r.transfer().amount()).isEqualTo(300);
        assertThat(r.transfer().debitAccountId()).isEqualTo(a.id());
        assertThat(r.transfer().ledger()).isEqualTo(USD);
        assertThat(r.transfer().pendingId()).isEqualTo(uuid(5));
        assertThat(r.resolvedStatus()).isEqualTo(PendingStatus.POSTED);
    }

    @Test
    void partialPostReleasesTheRemainder() {
        Applied r = applied(TransferEngine.apply(post(11, 5, 120), da, cb, open(null), NOW));
        assertThat(r.debit().debitsPending()).isZero();
        assertThat(r.debit().debitsPosted()).isEqualTo(120);
        assertThat(r.credit().creditsPending()).isZero();
        assertThat(r.credit().creditsPosted()).isEqualTo(120);
    }

    @Test
    void postMoreThanPendingIsRejected() {
        assertThat(code(TransferEngine.apply(post(11, 5, 301), da, cb, open(null), NOW)))
                .isEqualTo(TransferResult.EXCEEDS_PENDING_TRANSFER_AMOUNT);
    }

    @Test
    void voidReleasesPending() {
        Applied r = applied(TransferEngine.apply(voided(11, 5), da, cb, open(null), NOW));
        assertThat(r.debit().debitsPending()).isZero();
        assertThat(r.debit().debitsPosted()).isZero();
        assertThat(r.credit().creditsPending()).isZero();
        assertThat(r.transfer().amount()).isEqualTo(300);
        assertThat(r.resolvedStatus()).isEqualTo(PendingStatus.VOIDED);
    }

    @Test
    void voidWithExplicitFullAmountIsAccepted() {
        Transfer v = Transfer.request(uuid(11), null, null, 300, 0, 0, TransferFlags.VOID, uuid(5), 0, null);
        assertThat(TransferEngine.apply(v, da, cb, open(null), NOW)).isInstanceOf(Applied.class);
    }

    @Test
    void voidWithPartialAmountIsRejected() {
        Transfer v = Transfer.request(uuid(11), null, null, 100, 0, 0, TransferFlags.VOID, uuid(5), 0, null);
        assertThat(code(TransferEngine.apply(v, da, cb, open(null), NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_AMOUNT);
    }

    @Test
    void voidWithTooLargeAmountIsRejected() {
        Transfer v = Transfer.request(uuid(11), null, null, 400, 0, 0, TransferFlags.VOID, uuid(5), 0, null);
        assertThat(code(TransferEngine.apply(v, da, cb, open(null), NOW)))
                .isEqualTo(TransferResult.EXCEEDS_PENDING_TRANSFER_AMOUNT);
    }

    @Test
    void unknownPendingTransfer() {
        assertThat(code(TransferEngine.apply(post(11, 5, 0), da, cb, null, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_NOT_FOUND);
    }

    @Test
    void referencedTransferIsNotPending() {
        Transfer plain = single(5, a, b, 300).withStored(5, NOW);
        assertThat(code(TransferEngine.apply(post(11, 5, 0), da, cb, plain, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_NOT_PENDING);
    }

    @Test
    void alreadyPosted() {
        Transfer p = storedPending(5, a, b, 300, PendingStatus.POSTED, null);
        assertThat(code(TransferEngine.apply(post(11, 5, 0), da, cb, p, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_ALREADY_POSTED);
    }

    @Test
    void alreadyVoided() {
        Transfer p = storedPending(5, a, b, 300, PendingStatus.VOIDED, null);
        assertThat(code(TransferEngine.apply(voided(11, 5), da, cb, p, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_ALREADY_VOIDED);
    }

    @Test
    void alreadyExpired() {
        Transfer p = storedPending(5, a, b, 300, PendingStatus.EXPIRED, NOW.minusSeconds(1));
        assertThat(code(TransferEngine.apply(post(11, 5, 0), da, cb, p, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_EXPIRED);
    }

    @Test
    void pastExpiryCannotBePostedOrVoided() {
        Transfer p = open(NOW);
        assertThat(code(TransferEngine.apply(post(11, 5, 0), da, cb, p, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_EXPIRED);
        assertThat(code(TransferEngine.apply(voided(11, 5), da, cb, p, NOW.plusSeconds(10))))
                .isEqualTo(TransferResult.PENDING_TRANSFER_EXPIRED);
    }

    @Test
    void justBeforeExpiryStillWorks() {
        Transfer p = open(NOW.plusSeconds(1));
        assertThat(TransferEngine.apply(post(11, 5, 0), da, cb, p, NOW)).isInstanceOf(Applied.class);
    }

    @Test
    void givenFieldsMustMatchThePendingTransfer() {
        Transfer p = open(null);
        UUID other = uuid(99);
        assertThat(code(TransferEngine.apply(new Transfer(uuid(11), other, null, 0, 0, 0, TransferFlags.POST,
                uuid(5), 0, null, 0, null, null, null), da, cb, p, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_DEBIT_ACCOUNT_ID);
        assertThat(code(TransferEngine.apply(new Transfer(uuid(11), null, other, 0, 0, 0, TransferFlags.POST,
                uuid(5), 0, null, 0, null, null, null), da, cb, p, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_CREDIT_ACCOUNT_ID);
        assertThat(code(TransferEngine.apply(new Transfer(uuid(11), null, null, 0, 978, 0, TransferFlags.POST,
                uuid(5), 0, null, 0, null, null, null), da, cb, p, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_LEDGER);
        assertThat(code(TransferEngine.apply(new Transfer(uuid(11), null, null, 0, 0, 7, TransferFlags.POST,
                uuid(5), 0, null, 0, null, null, null), da, cb, p, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_CODE);
    }

    @Test
    void matchingExplicitFieldsAreAccepted() {
        Transfer explicit = new Transfer(uuid(11), a.id(), b.id(), 0, USD, 1, TransferFlags.POST, uuid(5), 0, null,
                0, null, null, null);
        assertThat(TransferEngine.apply(explicit, da, cb, open(null), NOW)).isInstanceOf(Applied.class);
    }

    @Test
    void missingAccountsOnResolve() {
        assertThat(code(TransferEngine.apply(post(11, 5, 0), null, cb, open(null), NOW)))
                .isEqualTo(TransferResult.DEBIT_ACCOUNT_NOT_FOUND);
        assertThat(code(TransferEngine.apply(post(11, 5, 0), da, null, open(null), NOW)))
                .isEqualTo(TransferResult.CREDIT_ACCOUNT_NOT_FOUND);
    }

    @Test
    void postOverflowIsRejected() {
        Account d = funded(1, AccountFlags.NONE, 10, Long.MAX_VALUE - 5, 0, 0);
        Transfer p = storedPending(5, d, b, 10, PendingStatus.PENDING, null);
        Account c = funded(2, AccountFlags.NONE, 0, 0, 10, 0);
        assertThat(code(TransferEngine.apply(post(11, 5, 0), d, c, p, NOW)))
                .isEqualTo(TransferResult.OVERFLOWS_BALANCE);
    }

    @Test
    void closedAccountsCanStillBeResolved() {
        Account d = funded(1, closed(), 300, 0, 0, 0);
        assertThat(TransferEngine.apply(voided(11, 5), d, cb, open(null), NOW)).isInstanceOf(Applied.class);
    }

    // ---- expiry ----

    @Test
    void expireReleasesPending() {
        Transfer p = open(NOW.minusSeconds(1));
        Applied r = applied(TransferEngine.expire(p, da, cb, NOW));
        assertThat(r.debit().debitsPending()).isZero();
        assertThat(r.credit().creditsPending()).isZero();
        assertThat(r.transfer().pendingStatus()).isEqualTo(PendingStatus.EXPIRED);
        assertThat(r.resolvedStatus()).isEqualTo(PendingStatus.EXPIRED);
    }

    @Test
    void expireRejectsNonOpenOrNotYetDue() {
        assertThat(code(TransferEngine.expire(storedPending(5, a, b, 300, PendingStatus.POSTED, NOW.minusSeconds(1)),
                da, cb, NOW))).isEqualTo(TransferResult.PENDING_TRANSFER_NOT_PENDING);
        assertThat(code(TransferEngine.expire(single(5, a, b, 3).withStored(1, NOW), da, cb, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_NOT_PENDING);
        assertThat(code(TransferEngine.expire(open(NOW.plusSeconds(5)), da, cb, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_NOT_PENDING);
        assertThat(code(TransferEngine.expire(open(null), da, cb, NOW)))
                .isEqualTo(TransferResult.PENDING_TRANSFER_NOT_PENDING);
    }

    // ---- idempotency helpers ----

    @Test
    void normalizeFillsInheritedFields() {
        Transfer n = TransferEngine.normalize(post(11, 5, 0), open(null));
        assertThat(n.debitAccountId()).isEqualTo(a.id());
        assertThat(n.creditAccountId()).isEqualTo(b.id());
        assertThat(n.amount()).isEqualTo(300);
        assertThat(n.ledger()).isEqualTo(USD);
        assertThat(n.code()).isEqualTo(1);
    }

    @Test
    void normalizeKeepsExplicitValuesAndIgnoresOtherTransfers() {
        Transfer explicit = new Transfer(uuid(11), a.id(), b.id(), 50, USD, 1, TransferFlags.POST, uuid(5), 0, null,
                0, null, null, null);
        assertThat(TransferEngine.normalize(explicit, open(null))).isEqualTo(explicit);
        Transfer plain = single(10, a, b, 5);
        assertThat(TransferEngine.normalize(plain, open(null))).isSameAs(plain);
        Transfer p = post(11, 5, 0);
        assertThat(TransferEngine.normalize(p, null)).isSameAs(p);
    }

    @Test
    void differingFieldsNamesEveryDifference() {
        Transfer stored = new Transfer(uuid(10), a.id(), b.id(), 5, USD, 1, TransferFlags.PENDING, null, 10, "x",
                3, NOW, PendingStatus.PENDING, NOW.plusSeconds(10));
        assertThat(TransferEngine.differingFields(stored, stored)).isEmpty();
        Transfer other = new Transfer(uuid(10), b.id(), a.id(), 6, 978, 2, TransferFlags.NONE, uuid(1), 11, "y",
                0, null, null, null);
        assertThat(TransferEngine.differingFields(stored, other)).containsExactly("debitAccountId",
                "creditAccountId", "amount", "ledger", "code", "flags", "pendingId", "timeoutSeconds", "reference");
    }
}
