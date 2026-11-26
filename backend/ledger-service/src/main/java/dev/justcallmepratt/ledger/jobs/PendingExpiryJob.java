package dev.justcallmepratt.ledger.jobs;

import dev.justcallmepratt.ledger.app.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Releases pending transfers whose {@code expires_at} has passed and emits {@code transfer.expired}. */
@Component
public class PendingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingExpiryJob.class);

    private final TransferService transfers;

    public PendingExpiryJob(TransferService transfers) {
        this.transfers = transfers;
    }

    @Scheduled(fixedDelayString = "${ledger.expiry.interval-ms:5000}")
    public void tick() {
        try {
            expireDue();
        } catch (RuntimeException e) {
            log.warn("Pending expiry run failed, will retry: {}", e.toString());
        }
    }

    /** Expires every due pending transfer (one transaction each); returns how many were released. */
    public int expireDue() {
        int expired = 0;
        for (UUID id : transfers.dueForExpiry()) {
            try {
                if (transfers.expire(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire pending transfer {}: {}", id, e.toString());
            }
        }
        return expired;
    }
}
