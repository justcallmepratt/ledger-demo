package dev.justcallmepratt.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Account snapshot with the four TigerBeetle counters, all in minor units and never negative.
 */
public record Account(UUID id,
                      int ledger,
                      int code,
                      String name,
                      AccountFlags flags,
                      long debitsPending,
                      long debitsPosted,
                      long creditsPending,
                      long creditsPosted,
                      Instant createdAt) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(flags, "flags");
        if (debitsPending < 0 || debitsPosted < 0 || creditsPending < 0 || creditsPosted < 0) {
            throw new IllegalArgumentException("account counters must not be negative");
        }
    }

    public static Account open(UUID id, int ledger, int code, String name, AccountFlags flags, Instant createdAt) {
        return new Account(id, ledger, code, name, flags, 0, 0, 0, 0, createdAt);
    }

    public Account withCounters(long debitsPending, long debitsPosted, long creditsPending, long creditsPosted) {
        return new Account(id, ledger, code, name, flags,
                debitsPending, debitsPosted, creditsPending, creditsPosted, createdAt);
    }
}
