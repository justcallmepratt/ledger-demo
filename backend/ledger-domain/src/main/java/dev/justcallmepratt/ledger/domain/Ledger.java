package dev.justcallmepratt.ledger.domain;

/** A ledger groups accounts that may transact: one asset. The id is the ISO 4217 numeric code. */
public record Ledger(int id, String currency, int assetScale) {
}
