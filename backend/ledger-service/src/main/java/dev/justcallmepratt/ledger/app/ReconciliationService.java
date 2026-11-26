package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.app.ReconciliationReport.AccountMismatch;
import dev.justcallmepratt.ledger.app.ReconciliationReport.LedgerLine;
import dev.justcallmepratt.ledger.persistence.CounterComparison;
import dev.justcallmepratt.ledger.persistence.LedgerTotals;
import dev.justcallmepratt.ledger.persistence.ReconciliationRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proves the books balance. Per account: recompute the four counters from the immutable
 * transfers and compare with the stored ones. Per ledger: check that the sum of debits equals
 * the sum of credits, posted and pending. Runs in one read-only REPEATABLE READ transaction
 * so concurrent writes cannot cause false mismatches. Updates the gauge
 * {@code ledger.reconciliation.mismatches}.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private record Snapshot(List<CounterComparison> comparisons, List<LedgerTotals> totals) {
    }

    private final ReconciliationRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AtomicInteger mismatches = new AtomicInteger();

    public ReconciliationService(ReconciliationRepository repository, PlatformTransactionManager txManager,
                                 Clock clock, MeterRegistry registry) {
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.tx.setReadOnly(true);
        this.clock = clock;
        Gauge.builder("ledger.reconciliation.mismatches", mismatches, AtomicInteger::get)
                .description("Accounts whose counters differ from the transfers, plus unbalanced ledgers")
                .register(registry);
    }

    public ReconciliationReport run() {
        Snapshot snapshot = tx.execute(status -> new Snapshot(repository.compareCounters(),
                repository.ledgerTotals()));
        List<CounterComparison> comparisons = snapshot.comparisons();
        List<LedgerTotals> totals = snapshot.totals();

        List<AccountMismatch> accountMismatches = new ArrayList<>();
        for (CounterComparison c : comparisons) {
            check(accountMismatches, c, "debitsPending", c.storedDebitsPending(), c.recomputedDebitsPending());
            check(accountMismatches, c, "debitsPosted", c.storedDebitsPosted(), c.recomputedDebitsPosted());
            check(accountMismatches, c, "creditsPending", c.storedCreditsPending(), c.recomputedCreditsPending());
            check(accountMismatches, c, "creditsPosted", c.storedCreditsPosted(), c.recomputedCreditsPosted());
        }
        List<LedgerLine> lines = new ArrayList<>();
        boolean allBalanced = accountMismatches.isEmpty();
        for (LedgerTotals t : totals) {
            boolean balanced = t.debitsPosted().equals(t.creditsPosted()) && t.debitsPending().equals(t.creditsPending());
            allBalanced &= balanced;
            lines.add(new LedgerLine(t.ledger(), t.debitsPosted().toString(), t.creditsPosted().toString(),
                    t.debitsPending().toString(), t.creditsPending().toString(), balanced));
        }
        ReconciliationReport report = new ReconciliationReport(clock.instant(), allBalanced, lines,
                accountMismatches);
        mismatches.set(report.problemCount());
        if (!report.balanced()) {
            log.error("Reconciliation FAILED: {} account mismatch(es), {} unbalanced ledger(s): {}",
                    accountMismatches.size(), lines.stream().filter(l -> !l.balanced()).count(), accountMismatches);
        }
        return report;
    }

    private static void check(List<AccountMismatch> out, CounterComparison c, String field, long stored,
                              BigInteger recomputed) {
        if (!BigInteger.valueOf(stored).equals(recomputed)) {
            out.add(new AccountMismatch(c.accountId(), field, Long.toString(stored), recomputed.toString()));
        }
    }
}
