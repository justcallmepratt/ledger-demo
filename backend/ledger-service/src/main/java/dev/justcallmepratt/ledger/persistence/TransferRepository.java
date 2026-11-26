package dev.justcallmepratt.ledger.persistence;

import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transfers are insert-only. Pending lifecycle lives in {@code pending_transfers}. */
@Repository
public class TransferRepository {

    private static final String SELECT = "SELECT t.seq, t.id, t.debit_account_id, t.credit_account_id, t.amount, "
            + "t.ledger, t.code, t.linked, t.pending, t.post_pending, t.void_pending, t.pending_id, "
            + "t.timeout_seconds, t.expires_at, t.reference, t.created_at, p.status AS pending_status ";

    private static final RowMapper<Transfer> MAPPER = (rs, n) -> {
        String status = rs.getString("pending_status");
        return new Transfer(
                rs.getObject("id", UUID.class),
                rs.getObject("debit_account_id", UUID.class),
                rs.getObject("credit_account_id", UUID.class),
                rs.getLong("amount"),
                rs.getInt("ledger"),
                rs.getInt("code"),
                new TransferFlags(rs.getBoolean("linked"), rs.getBoolean("pending"),
                        rs.getBoolean("post_pending"), rs.getBoolean("void_pending")),
                rs.getObject("pending_id", UUID.class),
                rs.getInt("timeout_seconds"),
                rs.getString("reference"),
                rs.getLong("seq"),
                Db.instant(rs, "created_at"),
                status == null ? null : PendingStatus.valueOf(status),
                Db.instant(rs, "expires_at"));
    };

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc.sql(SELECT + "FROM transfers t LEFT JOIN pending_transfers p ON p.transfer_id = t.id "
                        + "WHERE t.id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Inserts the transfer and returns its {@code seq}, or empty if the id already exists
     * (including a concurrent insert that committed first).
     */
    public Optional<Long> insert(Transfer t) {
        return jdbc.sql("INSERT INTO transfers (id, debit_account_id, credit_account_id, amount, ledger, code, "
                        + "linked, pending, post_pending, void_pending, pending_id, timeout_seconds, expires_at, "
                        + "reference, created_at) VALUES (:id, :debit, :credit, :amount, :ledger, :code, :linked, "
                        + ":pending, :post, :void, :pendingId, :timeout, :expiresAt, :reference, :createdAt) "
                        + "ON CONFLICT (id) DO NOTHING RETURNING seq")
                .param("id", t.id())
                .param("debit", t.debitAccountId())
                .param("credit", t.creditAccountId())
                .param("amount", t.amount())
                .param("ledger", t.ledger())
                .param("code", t.code())
                .param("linked", t.flags().linked())
                .param("pending", t.flags().pending())
                .param("post", t.flags().postPendingTransfer())
                .param("void", t.flags().voidPendingTransfer())
                .param("pendingId", t.pendingId())
                .param("timeout", t.timeoutSeconds())
                .param("expiresAt", Db.ts(t.expiresAt()))
                .param("reference", t.reference())
                .param("createdAt", Db.ts(t.timestamp()))
                .query(Long.class)
                .optional();
    }

    public void insertPending(UUID transferId, long seq, Instant expiresAt) {
        jdbc.sql("INSERT INTO pending_transfers (transfer_id, seq, status, expires_at) "
                        + "VALUES (:id, :seq, 'PENDING', :expiresAt)")
                .param("id", transferId)
                .param("seq", seq)
                .param("expiresAt", Db.ts(expiresAt))
                .update();
    }

    /** Moves an open pending transfer to its final status. Returns false if it was not open. */
    public boolean resolvePending(UUID pendingId, PendingStatus status, UUID resolvedBy, Instant at) {
        int rows = jdbc.sql("UPDATE pending_transfers SET status = :status, resolved_by = :by, resolved_at = :at "
                        + "WHERE transfer_id = :id AND status = 'PENDING'")
                .param("status", status.name())
                .param("by", resolvedBy)
                .param("at", Db.ts(at))
                .param("id", pendingId)
                .update();
        return rows == 1;
    }

    /** Ids of open pending transfers whose expiry has passed, oldest expiry first. */
    public List<UUID> findDueIds(Instant now, int limit) {
        return jdbc.sql("SELECT transfer_id FROM pending_transfers WHERE status = 'PENDING' "
                        + "AND expires_at IS NOT NULL AND expires_at <= :now ORDER BY expires_at LIMIT :limit")
                .param("now", Db.ts(now))
                .param("limit", limit)
                .query((rs, n) -> rs.getObject("transfer_id", UUID.class))
                .list();
    }

    /** Newest first by seq, strictly below {@code beforeSeq}; optional pending status filter. */
    public List<Transfer> page(long beforeSeq, PendingStatus status, int limit) {
        if (status == null) {
            return jdbc.sql(SELECT + "FROM transfers t LEFT JOIN pending_transfers p ON p.transfer_id = t.id "
                            + "WHERE t.seq < :before ORDER BY t.seq DESC LIMIT :limit")
                    .param("before", beforeSeq)
                    .param("limit", limit)
                    .query(MAPPER)
                    .list();
        }
        return jdbc.sql(SELECT + "FROM pending_transfers p JOIN transfers t ON t.id = p.transfer_id "
                        + "WHERE p.status = :status AND p.seq < :before ORDER BY p.seq DESC LIMIT :limit")
                .param("status", status.name())
                .param("before", beforeSeq)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    /** Transfers touching the account, newest first by seq. One index scan per side, merged. */
    public List<Transfer> pageForAccount(UUID accountId, long beforeSeq, int limit) {
        return jdbc.sql(SELECT + "FROM ("
                        + "(SELECT * FROM transfers WHERE debit_account_id = :acct AND seq < :before "
                        + "ORDER BY seq DESC LIMIT :limit) "
                        + "UNION ALL "
                        + "(SELECT * FROM transfers WHERE credit_account_id = :acct AND seq < :before "
                        + "ORDER BY seq DESC LIMIT :limit)"
                        + ") t LEFT JOIN pending_transfers p ON p.transfer_id = t.id "
                        + "ORDER BY t.seq DESC LIMIT :limit")
                .param("acct", accountId)
                .param("before", beforeSeq)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }
}
