package dev.justcallmepratt.ledger.api.dto;

import java.util.UUID;

/** Counters of an account right after a transfer, as embedded in transfer events. */
public record AccountBalanceView(UUID id, String debitsPending, String debitsPosted, String creditsPending,
                                 String creditsPosted) {
}
