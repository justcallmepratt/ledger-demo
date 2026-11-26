package dev.justcallmepratt.ledger.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Account as served by the API: counters are decimal strings in minor units. */
public record AccountResponse(UUID id, int ledger, int code, String name, AccountFlagsView flags,
                              String debitsPending, String debitsPosted, String creditsPending,
                              String creditsPosted, Instant createdAt) {
}
