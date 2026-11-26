package dev.justcallmepratt.ledger.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Transfer as served by the API: amount and seq are decimal strings. */
public record TransferResponse(UUID id, UUID debitAccountId, UUID creditAccountId, String amount, int ledger,
                               int code, TransferFlagsView flags, UUID pendingId, int timeoutSeconds,
                               String reference, String seq, Instant timestamp, String pendingStatus,
                               Instant expiresAt) {
}
