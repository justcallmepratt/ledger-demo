package dev.justcallmepratt.ledger.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ActivityRepository {

    private static final String COLUMNS = "event_id, type, seq, transfer_id, account_id, amount, ledger, "
            + "occurred_at, summary";

    private static final RowMapper<ActivityEntry> MAPPER = (rs, n) -> {
        long seq = rs.getLong("seq");
        Long seqOrNull = rs.wasNull() ? null : seq;
        long amount = rs.getLong("amount");
        Long amountOrNull = rs.wasNull() ? null : amount;
        return new ActivityEntry(
                rs.getObject("event_id", UUID.class),
                rs.getString("type"),
                seqOrNull,
                rs.getObject("transfer_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                amountOrNull,
                rs.getInt("ledger"),
                Db.instant(rs, "occurred_at"),
                rs.getString("summary"));
    };

    private final JdbcClient jdbc;

    public ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the entry unless its event id was already projected. True if inserted (dedupe). */
    public boolean insertIfAbsent(ActivityEntry e) {
        int rows = jdbc.sql("INSERT INTO activity (event_id, type, seq, transfer_id, account_id, amount, ledger, "
                        + "occurred_at, summary) VALUES (:eventId, :type, :seq, :transferId, :accountId, :amount, "
                        + ":ledger, :occurredAt, :summary) ON CONFLICT (event_id) DO NOTHING")
                .param("eventId", e.eventId())
                .param("type", e.type())
                .param("seq", e.seq())
                .param("transferId", e.transferId())
                .param("accountId", e.accountId())
                .param("amount", e.amount())
                .param("ledger", e.ledger())
                .param("occurredAt", Db.ts(e.occurredAt()))
                .param("summary", e.summary())
                .update();
        return rows == 1;
    }

    /** Newest first. */
    public List<ActivityEntry> latest(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM activity ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    /** Entries projected after the given event, oldest first (SSE Last-Event-ID replay). */
    public Optional<List<ActivityEntry>> after(UUID eventId, int limit) {
        boolean known = jdbc.sql("SELECT count(*) FROM activity WHERE event_id = :id")
                .param("id", eventId)
                .query(Long.class)
                .single() > 0;
        if (!known) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql("SELECT " + COLUMNS + " FROM activity WHERE id > "
                        + "(SELECT id FROM activity WHERE event_id = :id) ORDER BY id LIMIT :limit")
                .param("id", eventId)
                .param("limit", limit)
                .query(MAPPER)
                .list());
    }

    /** Keeps only the newest {@code keep} rows. */
    public void prune(int keep) {
        jdbc.sql("DELETE FROM activity WHERE id <= (SELECT id FROM activity ORDER BY id DESC OFFSET :keep LIMIT 1)")
                .param("keep", keep)
                .update();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM activity").query(Long.class).single();
    }
}
