package dev.justcallmepratt.ledger.rules;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import dev.justcallmepratt.ledger.domain.TransferResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure transfer rules. Validates a transfer in the documented order (first failure wins)
 * and applies it to account snapshots. Business failures are returned as {@link Rejected},
 * never thrown. Arithmetic uses {@code Math.addExact}/{@code subtractExact}.
 *
 * <p>Validation order: id valid, flags consistent, accounts exist, accounts differ, ledgers
 * match, amount positive (not for post/void), accounts not closed, balance limits, overflow.
 * Post/void first resolve the pending transfer (found, same fields, not resolved, not
 * expired) and then check the amount.
 */
public final class TransferEngine {

    private TransferEngine() {
    }

    public sealed interface Outcome permits Applied, Rejected {
    }

    /**
     * @param transfer       the normalised transfer to store (inherited fields filled, post/void
     *                       amount resolved, timestamp and expiry set, {@code seq} still 0)
     * @param debit          debit account after the transfer
     * @param credit         credit account after the transfer
     * @param resolvedStatus for post/void/expiry: the new status of the pending transfer; else null
     */
    public record Applied(Transfer transfer, Account debit, Account credit, PendingStatus resolvedStatus)
            implements Outcome {
    }

    public record Rejected(TransferResult code, String detail) implements Outcome {
        public Rejected(TransferResult code) {
            this(code, null);
        }
    }

    /**
     * Validates and applies one transfer.
     *
     * @param request the submitted transfer
     * @param debit   debit account snapshot, or null if it does not exist. For post/void:
     *                the pending transfer's debit account
     * @param credit  credit account snapshot, or null; for post/void the pending's credit account
     * @param pending for post/void, the referenced transfer with its current status; else ignored
     * @param now     the transaction time (UTC)
     */
    public static Outcome apply(Transfer request, Account debit, Account credit, Transfer pending, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        if (isNil(request.id())) {
            return reject(TransferResult.ID_MUST_NOT_BE_NIL);
        }
        TransferFlags flags = request.flags();
        int phases = (flags.pending() ? 1 : 0) + (flags.postPendingTransfer() ? 1 : 0)
                + (flags.voidPendingTransfer() ? 1 : 0);
        if (phases > 1) {
            return reject(TransferResult.FLAGS_ARE_MUTUALLY_EXCLUSIVE);
        }
        if (flags.resolves()) {
            if (isNil(request.pendingId())) {
                return reject(TransferResult.PENDING_ID_REQUIRED);
            }
        } else if (request.pendingId() != null) {
            return reject(TransferResult.PENDING_ID_MUST_BE_NULL);
        }
        if (request.timeoutSeconds() != 0 && !flags.pending()) {
            return reject(TransferResult.TIMEOUT_RESERVED_FOR_PENDING);
        }
        return flags.resolves() ? resolve(request, debit, credit, pending, now) : create(request, debit, credit, now);
    }

    private static Outcome create(Transfer request, Account debit, Account credit, Instant now) {
        if (debit == null) {
            return reject(TransferResult.DEBIT_ACCOUNT_NOT_FOUND);
        }
        if (credit == null) {
            return reject(TransferResult.CREDIT_ACCOUNT_NOT_FOUND);
        }
        if (!debit.id().equals(request.debitAccountId()) || !credit.id().equals(request.creditAccountId())) {
            throw new IllegalArgumentException("account snapshots do not match the transfer's account ids");
        }
        if (debit.id().equals(credit.id())) {
            return reject(TransferResult.ACCOUNTS_MUST_BE_DIFFERENT);
        }
        if (debit.ledger() != credit.ledger()) {
            return reject(TransferResult.LEDGER_MUST_MATCH);
        }
        if (request.ledger() != debit.ledger()) {
            return reject(TransferResult.TRANSFER_MUST_HAVE_SAME_LEDGER_AS_ACCOUNTS);
        }
        long amount = request.amount();
        if (amount == 0) {
            return reject(TransferResult.AMOUNT_MUST_BE_POSITIVE);
        }
        if (debit.flags().closed()) {
            return reject(TransferResult.DEBIT_ACCOUNT_CLOSED);
        }
        if (credit.flags().closed()) {
            return reject(TransferResult.CREDIT_ACCOUNT_CLOSED);
        }
        Optional<TransferResult> limit = BalanceRules.checkLimits(debit, credit, amount);
        if (limit.isPresent()) {
            return reject(limit.get());
        }
        boolean pendingPhase = request.flags().pending();
        if (BalanceRules.overflows(debit.debitsPending(), debit.debitsPosted(), amount, pendingPhase)
                || BalanceRules.overflows(credit.creditsPending(), credit.creditsPosted(), amount, pendingPhase)) {
            return reject(TransferResult.OVERFLOWS_BALANCE);
        }
        Account newDebit;
        Account newCredit;
        Transfer stored;
        if (pendingPhase) {
            newDebit = debit.withCounters(Math.addExact(debit.debitsPending(), amount), debit.debitsPosted(),
                    debit.creditsPending(), debit.creditsPosted());
            newCredit = credit.withCounters(credit.debitsPending(), credit.debitsPosted(),
                    Math.addExact(credit.creditsPending(), amount), credit.creditsPosted());
            Instant expiresAt = request.timeoutSeconds() > 0 ? now.plusSeconds(request.timeoutSeconds()) : null;
            stored = new Transfer(request.id(), debit.id(), credit.id(), amount, request.ledger(), request.code(),
                    request.flags(), null, request.timeoutSeconds(), request.reference(), 0L, now,
                    PendingStatus.PENDING, expiresAt);
        } else {
            newDebit = debit.withCounters(debit.debitsPending(), Math.addExact(debit.debitsPosted(), amount),
                    debit.creditsPending(), debit.creditsPosted());
            newCredit = credit.withCounters(credit.debitsPending(), credit.debitsPosted(),
                    credit.creditsPending(), Math.addExact(credit.creditsPosted(), amount));
            stored = new Transfer(request.id(), debit.id(), credit.id(), amount, request.ledger(), request.code(),
                    request.flags(), null, 0, request.reference(), 0L, now, null, null);
        }
        return new Applied(stored, newDebit, newCredit, null);
    }

    private static Outcome resolve(Transfer request, Account debit, Account credit, Transfer pending, Instant now) {
        if (pending == null) {
            return reject(TransferResult.PENDING_TRANSFER_NOT_FOUND);
        }
        if (!pending.flags().pending() || pending.pendingStatus() == null) {
            return reject(TransferResult.PENDING_TRANSFER_NOT_PENDING);
        }
        if (request.debitAccountId() != null && !request.debitAccountId().equals(pending.debitAccountId())) {
            return reject(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_DEBIT_ACCOUNT_ID);
        }
        if (request.creditAccountId() != null && !request.creditAccountId().equals(pending.creditAccountId())) {
            return reject(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_CREDIT_ACCOUNT_ID);
        }
        if (request.ledger() != 0 && request.ledger() != pending.ledger()) {
            return reject(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_LEDGER);
        }
        if (request.code() != 0 && request.code() != pending.code()) {
            return reject(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_CODE);
        }
        switch (pending.pendingStatus()) {
            case POSTED:
                return reject(TransferResult.PENDING_TRANSFER_ALREADY_POSTED);
            case VOIDED:
                return reject(TransferResult.PENDING_TRANSFER_ALREADY_VOIDED);
            case EXPIRED:
                return reject(TransferResult.PENDING_TRANSFER_EXPIRED);
            default:
                break;
        }
        if (pending.expiresAt() != null && !now.isBefore(pending.expiresAt())) {
            return reject(TransferResult.PENDING_TRANSFER_EXPIRED);
        }
        long pendingAmount = pending.amount();
        long amount = request.amount() == 0 ? pendingAmount : request.amount();
        if (amount > pendingAmount) {
            return reject(TransferResult.EXCEEDS_PENDING_TRANSFER_AMOUNT);
        }
        boolean post = request.flags().postPendingTransfer();
        if (!post && amount < pendingAmount) {
            return reject(TransferResult.PENDING_TRANSFER_HAS_DIFFERENT_AMOUNT);
        }
        if (debit == null) {
            return reject(TransferResult.DEBIT_ACCOUNT_NOT_FOUND);
        }
        if (credit == null) {
            return reject(TransferResult.CREDIT_ACCOUNT_NOT_FOUND);
        }
        long newDebitPending = Math.subtractExact(debit.debitsPending(), pendingAmount);
        long newCreditPending = Math.subtractExact(credit.creditsPending(), pendingAmount);
        long newDebitPosted = debit.debitsPosted();
        long newCreditPosted = credit.creditsPosted();
        if (post) {
            try {
                newDebitPosted = Math.addExact(newDebitPosted, amount);
                newCreditPosted = Math.addExact(newCreditPosted, amount);
            } catch (ArithmeticException overflow) {
                return reject(TransferResult.OVERFLOWS_BALANCE);
            }
        }
        Account newDebit = debit.withCounters(newDebitPending, newDebitPosted, debit.creditsPending(),
                debit.creditsPosted());
        Account newCredit = credit.withCounters(credit.debitsPending(), credit.debitsPosted(), newCreditPending,
                newCreditPosted);
        Transfer stored = new Transfer(request.id(), pending.debitAccountId(), pending.creditAccountId(), amount,
                pending.ledger(), pending.code(), request.flags(), pending.id(), 0, request.reference(), 0L, now,
                null, null);
        return new Applied(stored, newDebit, newCredit, post ? PendingStatus.POSTED : PendingStatus.VOIDED);
    }

    /**
     * Releases an open pending transfer whose {@code expiresAt} has passed. The returned
     * {@code transfer} is the pending transfer itself with status EXPIRED.
     */
    public static Outcome expire(Transfer pending, Account debit, Account credit, Instant now) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(debit, "debit");
        Objects.requireNonNull(credit, "credit");
        if (!pending.flags().pending() || pending.pendingStatus() != PendingStatus.PENDING) {
            return new Rejected(TransferResult.PENDING_TRANSFER_NOT_PENDING, "transfer is not an open pending transfer");
        }
        if (pending.expiresAt() == null || now.isBefore(pending.expiresAt())) {
            return new Rejected(TransferResult.PENDING_TRANSFER_NOT_PENDING, "pending transfer has not expired");
        }
        Account newDebit = debit.withCounters(Math.subtractExact(debit.debitsPending(), pending.amount()),
                debit.debitsPosted(), debit.creditsPending(), debit.creditsPosted());
        Account newCredit = credit.withCounters(credit.debitsPending(), credit.debitsPosted(),
                Math.subtractExact(credit.creditsPending(), pending.amount()), credit.creditsPosted());
        return new Applied(pending.withPendingStatus(PendingStatus.EXPIRED), newDebit, newCredit,
                PendingStatus.EXPIRED);
    }

    /**
     * Fills what a post/void request may omit (accounts, ledger, code, amount 0 = full pending
     * amount) from the pending transfer, so it can be compared with a stored transfer.
     * Other requests, and requests whose pending transfer is unknown, are returned unchanged.
     */
    public static Transfer normalize(Transfer request, Transfer pending) {
        if (!request.flags().resolves() || pending == null) {
            return request;
        }
        return new Transfer(request.id(),
                request.debitAccountId() != null ? request.debitAccountId() : pending.debitAccountId(),
                request.creditAccountId() != null ? request.creditAccountId() : pending.creditAccountId(),
                request.amount() != 0 ? request.amount() : pending.amount(),
                request.ledger() != 0 ? request.ledger() : pending.ledger(),
                request.code() != 0 ? request.code() : pending.code(),
                request.flags(), request.pendingId(), request.timeoutSeconds(), request.reference(),
                request.seq(), request.timestamp(), request.pendingStatus(), request.expiresAt());
    }

    /**
     * Client-supplied fields (JSON names) that differ between a stored transfer and a
     * (normalised) incoming one with the same id. Empty means an identical replay.
     */
    public static List<String> differingFields(Transfer stored, Transfer incoming) {
        List<String> diff = new ArrayList<>();
        if (!Objects.equals(stored.debitAccountId(), incoming.debitAccountId())) {
            diff.add("debitAccountId");
        }
        if (!Objects.equals(stored.creditAccountId(), incoming.creditAccountId())) {
            diff.add("creditAccountId");
        }
        if (stored.amount() != incoming.amount()) {
            diff.add("amount");
        }
        if (stored.ledger() != incoming.ledger()) {
            diff.add("ledger");
        }
        if (stored.code() != incoming.code()) {
            diff.add("code");
        }
        if (!stored.flags().equals(incoming.flags())) {
            diff.add("flags");
        }
        if (!Objects.equals(stored.pendingId(), incoming.pendingId())) {
            diff.add("pendingId");
        }
        if (stored.timeoutSeconds() != incoming.timeoutSeconds()) {
            diff.add("timeoutSeconds");
        }
        if (!Objects.equals(stored.reference(), incoming.reference())) {
            diff.add("reference");
        }
        return diff;
    }

    private static boolean isNil(UUID id) {
        return id == null || (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0);
    }

    private static Rejected reject(TransferResult code) {
        return new Rejected(code);
    }
}
