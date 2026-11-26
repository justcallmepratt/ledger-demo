package dev.justcallmepratt.ledger.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/** One real PostgreSQL for the whole test JVM, started on first use. No Docker needed. */
public final class SharedPostgres {

    private static final EmbeddedPostgres INSTANCE = start();

    private SharedPostgres() {
    }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // shutting down
                }
            }));
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException("could not start embedded PostgreSQL", e);
        }
    }

    public static String jdbcUrl() {
        return INSTANCE.getJdbcUrl("postgres", "postgres");
    }
}
