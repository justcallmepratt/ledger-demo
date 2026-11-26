package dev.justcallmepratt.ledger.jobs;

import dev.justcallmepratt.ledger.app.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic reconciliation; mismatches are logged at ERROR and exposed as a Micrometer gauge. */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService service;

    public ReconciliationJob(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ledger.reconciliation.interval-ms:300000}",
            initialDelayString = "${ledger.reconciliation.initial-delay-ms:60000}")
    public void tick() {
        try {
            service.run();
        } catch (RuntimeException e) {
            log.warn("Reconciliation run failed, will retry: {}", e.toString());
        }
    }
}
