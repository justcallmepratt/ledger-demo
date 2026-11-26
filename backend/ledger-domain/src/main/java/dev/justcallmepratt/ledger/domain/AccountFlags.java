package dev.justcallmepratt.ledger.domain;

/** Behaviour switches of an account. All default to false. */
public record AccountFlags(boolean debitsMustNotExceedCredits,
                           boolean creditsMustNotExceedDebits,
                           boolean history,
                           boolean closed) {

    public static final AccountFlags NONE = new AccountFlags(false, false, false, false);
}
