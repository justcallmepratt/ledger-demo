package dev.justcallmepratt.ledger.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * Read-only queries for the reconciliation check. Recomputes each account's counters from
 * the immutable transfers:
 * <ul>
 *   <li>posted: single-phase transfers and post transfers (void transfers move nothing);</li>
 *   <li>pending: pending transfers that are still PENDING.</li>
 * </ul>
 */
@Repository
public class ReconciliationRepository {

    private static final String SIDE = "SELECT %s AS account_id, "
            + "CASE WHEN t.pending AND p.status = 'PENDING' THEN t.amount ELSE 0 END AS %s, "
            + "CASE WHEN NOT t.pending AND NOT t.void_pending THEN t.amount ELSE 0 END AS %s "
            + "FROM transfers t LEFT JOIN pending_transfers p ON p.transfer_id = t.id";

    private static final String RECOMPUTE = "SELECT a.id, a.debits_pending, a.debits_posted, a.credits_pending, "
            + "a.credits_posted, COALESCE(r.dp, 0) AS r_dp, COALESCE(r.dposted, 0) AS r_dposted, "
            + "COALESCE(r.cp, 0) AS r_cp, COALESCE(r.cposted, 0) AS r_cposted FROM accounts a LEFT JOIN ("
            + "SELECT account_id, SUM(dp) AS dp, SUM(dposted) AS dposted, SUM(cp) AS cp, SUM(cposted) AS cposted "
            + "FROM ("
            + "SELECT d.account_id, d.pend AS dp, d.posted AS dposted, 0 AS cp, 0 AS cposted FROM ("
            + String.format(SIDE, "t.debit_account_id", "pend", "posted") + ") d "
            + "UNION ALL "
            + "SELECT c.account_id, 0, 0, c.pend, c.posted FROM ("
            + String.format(SIDE, "t.credit_account_id", "pend", "posted") + ") c"
            + ") u GROUP BY account_id) r ON r.account_id = a.id ORDER BY a.id";

    private final JdbcClient jdbc;

    public ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CounterComparison> compareCounters() {
        return jdbc.sql(RECOMPUTE)
                .query((rs, n) -> new CounterComparison(
                        rs.getObject("id", UUID.class),
                        rs.getLong("debits_pending"),
                        rs.getLong("debits_posted"),
                        rs.getLong("credits_pending"),
                        rs.getLong("credits_posted"),
                        rs.getBigDecimal("r_dp").toBigInteger(),
                        rs.getBigDecimal("r_dposted").toBigInteger(),
                        rs.getBigDecimal("r_cp").toBigInteger(),
                        rs.getBigDecimal("r_cposted").toBigInteger()))
                .list();
    }

    public List<LedgerTotals> ledgerTotals() {
        return jdbc.sql("SELECT l.id, COALESCE(SUM(a.debits_pending), 0) AS dp, COALESCE(SUM(a.debits_posted), 0) AS dposted, "
                        + "COALESCE(SUM(a.credits_pending), 0) AS cp, COALESCE(SUM(a.credits_posted), 0) AS cposted "
                        + "FROM ledgers l LEFT JOIN accounts a ON a.ledger = l.id GROUP BY l.id ORDER BY l.id")
                .query((rs, n) -> new LedgerTotals(
                        rs.getInt("id"),
                        big(rs.getBigDecimal("dp")),
                        big(rs.getBigDecimal("dposted")),
                        big(rs.getBigDecimal("cp")),
                        big(rs.getBigDecimal("cposted"))))
                .list();
    }

    private static BigInteger big(java.math.BigDecimal value) {
        return value.toBigInteger();
    }
}
