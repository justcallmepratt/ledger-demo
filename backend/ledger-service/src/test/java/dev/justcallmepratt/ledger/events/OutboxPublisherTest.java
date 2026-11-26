package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.persistence.OutboxMessage;
import dev.justcallmepratt.ledger.persistence.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-11-26T08:00:00Z");

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        publisher = new OutboxPublisher(outbox, kafka, tm, Clock.fixed(NOW, ZoneOffset.UTC), 100, 1000);
    }

    private static OutboxMessage msg(long id) {
        UUID key = UUID.randomUUID();
        return new OutboxMessage(id, UUID.randomUUID(), "ledger.transfers.v1", key.toString(), "t", "{\"n\":" + id + "}",
                NOW);
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        CompletableFuture<SendResult<String, String>> f = new CompletableFuture<>();
        f.completeExceptionally(new IllegalStateException("broker down"));
        return f;
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sendsInOrderWithCloudEventHeaderAndMarksPublished() {
        when(outbox.lockUnpublished(100)).thenReturn(List.of(msg(1), msg(2)));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok());

        assertThat(publisher.publishBatch()).isEqualTo(2);

        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(captor.capture());
        ProducerRecord<String, String> first = captor.getAllValues().get(0);
        assertThat(first.topic()).isEqualTo("ledger.transfers.v1");
        assertThat(first.value()).isEqualTo("{\"n\":1}");
        assertThat(new String(first.headers().lastHeader("content-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("application/cloudevents+json");
        verify(outbox).markPublished(1L, NOW);
        verify(outbox).markPublished(2L, NOW);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aFailedSendStopsTheBatchAndKeepsTheRestUnpublished() {
        when(outbox.lockUnpublished(100)).thenReturn(List.of(msg(1), msg(2), msg(3)));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok(), failed(), ok());

        assertThat(publisher.publishBatch()).isEqualTo(1);

        verify(outbox).markPublished(1L, NOW);
        verify(outbox).markAttemptFailed(2L);
        verify(outbox, never()).markPublished(2L, NOW);
        verify(outbox, never()).markPublished(3L, NOW);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aProducerThatThrowsLeavesTheRemainingRowsForTheNextPoll() {
        when(outbox.lockUnpublished(100)).thenReturn(List.of(msg(1), msg(2)));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok()).thenThrow(new IllegalStateException("no metadata"));

        assertThat(publisher.publishBatch()).isEqualTo(1);

        verify(outbox).markPublished(1L, NOW);
        verify(outbox, never()).markPublished(2L, NOW);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void anEmptyOutboxSendsNothing() {
        when(outbox.lockUnpublished(100)).thenReturn(List.of());

        assertThat(publisher.publishBatch()).isZero();

        verify(kafka, never()).send(any(ProducerRecord.class));
        verify(outbox, never()).markPublished(anyLong(), any());
        verify(outbox, never()).markAttemptFailed(anyLong());
    }

    @Test
    void pollSwallowsFailuresSoTheSchedulerKeepsRunning() {
        when(outbox.lockUnpublished(100)).thenThrow(new IllegalStateException("db down"));

        publisher.poll();   // must not throw

        verify(outbox).lockUnpublished(100);
    }
}
