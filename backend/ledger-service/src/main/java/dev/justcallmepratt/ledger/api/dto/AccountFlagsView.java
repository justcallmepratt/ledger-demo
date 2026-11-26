package dev.justcallmepratt.ledger.api.dto;

public record AccountFlagsView(boolean debitsMustNotExceedCredits, boolean creditsMustNotExceedDebits,
                               boolean history, boolean closed) {
}
