package dev.justcallmepratt.ledger.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled jobs (outbox publisher, expiry, reconciliation, SSE heartbeat). Tests switch it off. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ledger.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
