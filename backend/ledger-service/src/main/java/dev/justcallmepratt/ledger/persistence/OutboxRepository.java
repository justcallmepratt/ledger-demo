package dev.justcallmepratt.ledger.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxMessage> MAPPER = (rs, n) -> new OutboxMessage(
            rs.getLong("id"),
            rs.getObject("event_id", UUID.class),
            rs.getString("topic"),
            rs.getString("message_key"),
            rs.getString("event_type"),
            rs.getString("payload_text"),
            Db.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OutboxMessage m) {
        jdbc.sql("INSERT INTO outbox (event_id, topic, message_key, event_type, payload, created_at) "
                        + "VALUES (:eventId, :topic, :key, :type, CAST(:payload AS jsonb), :createdAt)")
                .param("eventId", m.eventId())
                .param("topic", m.topic())
                .param("key", m.key())
                .param("type", m.type())
                .param("payload", m.payload())
                .param("createdAt", Db.ts(m.createdAt()))
                .update();
    }

    /** Oldest unpublished rows, locked for this transaction; rows locked by others are skipped. */
    public List<OutboxMessage> lockUnpublished(int limit) {
        return jdbc.sql("SELECT id, event_id, topic, message_key, event_type, CAST(payload AS text) AS payload_text, "
                        + "created_at FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT :limit "
                        + "FOR UPDATE SKIP LOCKED")
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    public void markPublished(long id, Instant at) {
        jdbc.sql("UPDATE outbox SET published_at = :at, attempts = attempts + 1 WHERE id = :id")
                .param("at", Db.ts(at))
                .param("id", id)
                .update();
    }

    public void markAttemptFailed(long id) {
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
                .param("id", id)
                .update();
    }

    public long countUnpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }
}
