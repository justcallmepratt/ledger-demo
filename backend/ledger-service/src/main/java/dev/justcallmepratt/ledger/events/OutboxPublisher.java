package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.persistence.OutboxMessage;
import dev.justcallmepratt.ledger.persistence.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka. Rows are read {@code FOR UPDATE SKIP LOCKED} in batches,
 * sent (producer: acks=all, idempotent) and marked published in one transaction, so several
 * instances can run side by side. Delivery is at least once: a crash between send and commit
 * re-sends, and consumers dedupe on the CloudEvents id. If a send fails, the successful
 * prefix is marked published and the rest stays for the next poll.
 */
@Component
@ConditionalOnProperty(name = "ledger.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    public static final String CONTENT_TYPE = "application/cloudevents+json";
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                           PlatformTransactionManager txManager, Clock clock,
                           @Value("${ledger.outbox.batch-size:100}") int batchSize,
                           @Value("${ledger.outbox.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval-ms:200}")
    public void poll() {
        try {
            publishBatch();
        } catch (RuntimeException e) {
            log.warn("Outbox publish failed, will retry: {}", e.toString());
        }
    }

    /** Publishes one batch; returns how many rows were sent and marked published. */
    public int publishBatch() {
        Integer published = tx.execute(status -> {
            List<OutboxMessage> batch = outbox.lockUnpublished(batchSize);
            List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(batch.size());
            for (OutboxMessage message : batch) {
                try {
                    futures.add(kafka.send(record(message)));
                } catch (RuntimeException e) {
                    log.warn("Could not hand outbox row {} to the producer: {}", message.id(), e.toString());
                    break;
                }
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            int ok = 0;
            for (int i = 0; i < futures.size(); i++) {
                OutboxMessage message = batch.get(i);
                try {
                    futures.get(i).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                    outbox.markPublished(message.id(), now);
                    ok++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Publishing outbox row {} failed: {}", message.id(), e.toString());
                    outbox.markAttemptFailed(message.id());
                    break;
                }
            }
            return ok;
        });
        return published == null ? 0 : published;
    }

    private static ProducerRecord<String, String> record(OutboxMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(message.topic(), message.key(),
                message.payload());
        record.headers().add("content-type", CONTENT_TYPE.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
