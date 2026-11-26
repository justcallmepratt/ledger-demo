package dev.justcallmepratt.ledger.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Result of a reconciliation run; serialised as-is by GET /api/v1/reconciliation. */
public record ReconciliationReport(Instant checkedAt, boolean balanced, List<LedgerLine> ledgers,
                                   List<AccountMismatch> accountMismatches) {

    /** Per-ledger sums of the stored counters (decimal strings, may exceed a long). */
    public record LedgerLine(int ledger, String debitsPosted, String creditsPosted, String debitsPending,
                             String creditsPending, boolean balanced) {
    }

    /** A stored counter that differs from the value recomputed from the transfers. */
    public record AccountMismatch(UUID accountId, String field, String stored, String recomputed) {
    }

    /** Mismatching accounts plus unbalanced ledgers: the value of the Micrometer gauge. */
    public int problemCount() {
        int unbalanced = (int) ledgers.stream().filter(l -> !l.balanced()).count();
        return accountMismatches.size() + unbalanced;
    }
}
