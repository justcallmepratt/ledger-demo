package dev.justcallmepratt.ledger.persistence;

import java.time.Instant;
import java.util.UUID;

/** A row of the transactional outbox. {@code id} is 0 before insertion. */
public record OutboxMessage(long id, UUID eventId, String topic, String key, String type, String payload,
                            Instant createdAt) {
}
