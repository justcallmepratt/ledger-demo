package dev.justcallmepratt.ledger.persistence;

import dev.justcallmepratt.ledger.domain.Account;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class BalanceHistoryRepository {

    private static final RowMapper<BalanceHistoryEntry> MAPPER = (rs, n) -> new BalanceHistoryEntry(
            rs.getLong("id"),
            rs.getObject("transfer_id", UUID.class),
            rs.getLong("seq"),
            Db.instant(rs, "recorded_at"),
            rs.getLong("debits_pending"),
            rs.getLong("debits_posted"),
            rs.getLong("credits_pending"),
            rs.getLong("credits_posted"));

    private final JdbcClient jdbc;

    public BalanceHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the account's counters after the transfer. */
    public void insert(Account after, UUID transferId, long seq, Instant at) {
        jdbc.sql("INSERT INTO account_balance_history (account_id, transfer_id, seq, recorded_at, debits_pending, "
                        + "debits_posted, credits_pending, credits_posted) "
                        + "VALUES (:account, :transfer, :seq, :at, :dpend, :dposted, :cpend, :cposted)")
                .param("account", after.id())
                .param("transfer", transferId)
                .param("seq", seq)
                .param("at", Db.ts(at))
                .param("dpend", after.debitsPending())
                .param("dposted", after.debitsPosted())
                .param("cpend", after.creditsPending())
                .param("cposted", after.creditsPosted())
                .update();
    }

    /** Newest first, strictly below {@code beforeId}. */
    public List<BalanceHistoryEntry> page(UUID accountId, long beforeId, int limit) {
        return jdbc.sql("SELECT id, transfer_id, seq, recorded_at, debits_pending, debits_posted, "
                        + "credits_pending, credits_posted FROM account_balance_history "
                        + "WHERE account_id = :account AND id < :before ORDER BY id DESC LIMIT :limit")
                .param("account", accountId)
                .param("before", beforeId)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }
}
