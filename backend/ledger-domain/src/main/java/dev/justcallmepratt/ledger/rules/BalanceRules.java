package dev.justcallmepratt.ledger.rules;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.TransferResult;

import java.util.Optional;

/** Balance limit and overflow rules. Overflow-safe: never throws for business outcomes. */
final class BalanceRules {

    private BalanceRules() {
    }

    /**
     * {@code debits_must_not_exceed_credits}: reject if {@code dp + dP + amount > cP} on the
     * debit account. {@code credits_must_not_exceed_debits}: reject if
     * {@code cp + cP + amount > dP} on the credit account. A sum that overflows a long
     * certainly exceeds any limit.
     */
    static Optional<TransferResult> checkLimits(Account debit, Account credit, long amount) {
        if (debit.flags().debitsMustNotExceedCredits()
                && exceeds(debit.debitsPending(), debit.debitsPosted(), amount, debit.creditsPosted())) {
            return Optional.of(TransferResult.EXCEEDS_CREDITS);
        }
        if (credit.flags().creditsMustNotExceedDebits()
                && exceeds(credit.creditsPending(), credit.creditsPosted(), amount, credit.debitsPosted())) {
            return Optional.of(TransferResult.EXCEEDS_DEBITS);
        }
        return Optional.empty();
    }

    private static boolean exceeds(long pending, long posted, long amount, long limit) {
        try {
            return Math.addExact(Math.addExact(pending, posted), amount) > limit;
        } catch (ArithmeticException overflow) {
            return true;
        }
    }

    /** True if adding {@code amount} to any of the three sums an account tracks would overflow. */
    static boolean overflows(long pending, long posted, long amount, boolean addsToPending) {
        try {
            Math.addExact(Math.addExact(pending, posted), amount);
            Math.addExact(addsToPending ? pending : posted, amount);
            return false;
        } catch (ArithmeticException overflow) {
            return true;
        }
    }
}
