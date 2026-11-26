package dev.justcallmepratt.ledger.api.dto;

/** All flags are optional and default to false. */
public record AccountFlagsRequest(Boolean debitsMustNotExceedCredits, Boolean creditsMustNotExceedDebits,
                                  Boolean history, Boolean closed) {
}
