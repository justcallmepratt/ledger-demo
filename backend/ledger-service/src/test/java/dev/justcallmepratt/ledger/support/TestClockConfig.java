package dev.justcallmepratt.ledger.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/** Replaces the system clock in integration tests with one the test controls. */
@TestConfiguration
public class TestClockConfig {

    public static final Instant START = Instant.parse("2026-11-26T08:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
