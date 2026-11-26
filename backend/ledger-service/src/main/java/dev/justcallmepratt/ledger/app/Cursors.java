package dev.justcallmepratt.ledger.app;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/** Opaque keyset cursors: URL-safe base64 of a small "kind:value" string. */
public final class Cursors {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private Cursors() {
    }

    public static int checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.invalid("limit", "must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    public static String encodeLong(long value) {
        return encode("n:" + value);
    }

    public static long decodeLong(String cursor) {
        String raw = decode(cursor);
        if (!raw.startsWith("n:")) {
            throw badCursor();
        }
        try {
            long value = Long.parseLong(raw.substring(2));
            if (value < 1) {
                throw badCursor();
            }
            return value;
        } catch (NumberFormatException e) {
            throw badCursor();
        }
    }

    public static String encodeAccount(Instant createdAt, UUID id) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        return encode("a:" + micros + ":" + id);
    }

    /** @return [createdAt, id] */
    public static Object[] decodeAccount(String cursor) {
        String[] parts = decode(cursor).split(":");
        if (parts.length != 3 || !parts[0].equals("a")) {
            throw badCursor();
        }
        try {
            Instant createdAt = Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS);
            return new Object[]{createdAt, UUID.fromString(parts[2])};
        } catch (RuntimeException e) {
            throw badCursor();
        }
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw badCursor();
        }
    }

    private static ApiException badCursor() {
        return ApiException.invalid("cursor", "is not a valid cursor");
    }
}
