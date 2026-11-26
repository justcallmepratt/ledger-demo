package dev.justcallmepratt.ledger.domain;

/** Lifecycle of a two-phase (pending) transfer. It resolves at most once. */
public enum PendingStatus {
    PENDING,
    POSTED,
    VOIDED,
    EXPIRED
}
