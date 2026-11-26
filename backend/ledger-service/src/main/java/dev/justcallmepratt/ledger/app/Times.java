package dev.justcallmepratt.ledger.app;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** PostgreSQL timestamptz keeps microseconds; truncating up front keeps replays and reads identical. */
final class Times {

    private Times() {
    }

    static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
