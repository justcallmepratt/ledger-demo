package dev.justcallmepratt.ledger.persistence;

import java.math.BigInteger;

/** Sums of the stored counters over all accounts of a ledger (BigInteger: sums may exceed a long). */
public record LedgerTotals(int ledger, BigInteger debitsPending, BigInteger debitsPosted,
                           BigInteger creditsPending, BigInteger creditsPosted) {
}
