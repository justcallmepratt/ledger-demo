package dev.justcallmepratt.ledger.api.dto;

import java.time.Instant;
import java.util.UUID;

public record BalanceEntryResponse(UUID transferId, String seq, Instant timestamp, String debitsPending,
                                   String debitsPosted, String creditsPending, String creditsPosted) {
}
