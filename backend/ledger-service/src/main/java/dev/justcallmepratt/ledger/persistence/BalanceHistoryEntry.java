package dev.justcallmepratt.ledger.persistence;

import java.time.Instant;
import java.util.UUID;

/** Balance of an account right after a transfer (only for accounts with the history flag). */
public record BalanceHistoryEntry(long id, UUID transferId, long seq, Instant timestamp,
                                  long debitsPending, long debitsPosted, long creditsPending, long creditsPosted) {
}
