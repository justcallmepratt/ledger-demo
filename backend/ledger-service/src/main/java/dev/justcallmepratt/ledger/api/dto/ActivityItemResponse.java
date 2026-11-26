package dev.justcallmepratt.ledger.api.dto;

import java.time.Instant;
import java.util.UUID;

/** One entry of the live activity feed. {@code seq} and {@code amount} are decimal strings or null. */
public record ActivityItemResponse(UUID eventId, String type, String seq, UUID transferId, UUID accountId,
                                   String amount, int ledger, Instant occurredAt, String summary) {
}
