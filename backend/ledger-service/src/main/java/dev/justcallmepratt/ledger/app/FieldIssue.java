package dev.justcallmepratt.ledger.app;

/** One validation problem: which field and why. */
public record FieldIssue(String field, String message) {
}
