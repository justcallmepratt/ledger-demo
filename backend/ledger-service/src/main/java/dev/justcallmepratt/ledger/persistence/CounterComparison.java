package dev.justcallmepratt.ledger.persistence;

import java.math.BigInteger;
import java.util.UUID;

/** Stored counters of an account next to the values recomputed from {@code transfers}. */
public record CounterComparison(UUID accountId,
                                long storedDebitsPending, long storedDebitsPosted,
                                long storedCreditsPending, long storedCreditsPosted,
                                BigInteger recomputedDebitsPending, BigInteger recomputedDebitsPosted,
                                BigInteger recomputedCreditsPending, BigInteger recomputedCreditsPosted) {
}
