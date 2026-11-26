package dev.justcallmepratt.ledger.persistence;

import java.time.Instant;
import java.util.UUID;

/** A row of the bounded activity read model. */
public record ActivityEntry(UUID eventId, String type, Long seq, UUID transferId, UUID accountId, Long amount,
                            int ledger, Instant occurredAt, String summary) {
}
