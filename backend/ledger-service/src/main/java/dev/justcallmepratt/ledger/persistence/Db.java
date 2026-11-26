package dev.justcallmepratt.ledger.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Small JDBC helpers. All timestamps are stored as timestamptz and handled as UTC instants. */
final class Db {

    private Db() {
    }

    /** Parameter value for a timestamptz column (pgjdbc does not bind {@link Instant} directly). */
    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
