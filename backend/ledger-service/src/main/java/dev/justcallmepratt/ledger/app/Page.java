package dev.justcallmepratt.ledger.app;

import java.util.List;

/** One page of a keyset-paginated listing. {@code nextCursor} is null on the last page. */
public record Page<T>(List<T> items, String nextCursor) {
}
