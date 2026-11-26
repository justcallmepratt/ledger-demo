package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.persistence.ActivityEntry;
import dev.justcallmepratt.ledger.persistence.ActivityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityBroadcasterTest {

    private final ActivityRepository repository = mock(ActivityRepository.class);
    private final ActivityBroadcaster broadcaster = new ActivityBroadcaster(repository);

    private static ActivityEntry entry(int n) {
        return new ActivityEntry(UUID.randomUUID(), "transfer.created", (long) n, UUID.randomUUID(), null, 10L, 840,
                Instant.parse("2026-11-26T08:00:00Z"), "entry " + n);
    }

    @Test
    void subscribersAreTrackedAndReceiveEntriesAndHeartbeats() {
        SseEmitter emitter = broadcaster.subscribe(null);

        assertThat(emitter).isNotNull();
        assertThat(broadcaster.subscribers()).isEqualTo(1);
        broadcaster.publish(entry(1));
        broadcaster.heartbeat();
        assertThat(broadcaster.subscribers()).isEqualTo(1);
        verify(repository, never()).after(any(), anyInt());
    }

    @Test
    void aCompletedEmitterIsDroppedOnTheNextSend() {
        SseEmitter emitter = broadcaster.subscribe("");
        emitter.complete();

        broadcaster.publish(entry(1));

        assertThat(broadcaster.subscribers()).isZero();
    }

    @Test
    void aCompletedEmitterIsDroppedOnHeartbeatToo() {
        SseEmitter emitter = broadcaster.subscribe(null);
        emitter.complete();

        broadcaster.heartbeat();

        assertThat(broadcaster.subscribers()).isZero();
    }

    @Test
    void lastEventIdReplaysMissedEntries() {
        UUID last = UUID.randomUUID();
        when(repository.after(last, 500)).thenReturn(Optional.of(List.of(entry(1), entry(2))));

        broadcaster.subscribe(last.toString());

        verify(repository).after(last, 500);
        assertThat(broadcaster.subscribers()).isEqualTo(1);
    }

    @Test
    void anUnknownLastEventIdFallsBackToTheLatestEntries() {
        UUID last = UUID.randomUUID();
        when(repository.after(last, 500)).thenReturn(Optional.empty());
        when(repository.latest(50)).thenReturn(List.of(entry(2), entry(1)));

        broadcaster.subscribe(last.toString());

        verify(repository).latest(50);
    }

    @Test
    void anInvalidLastEventIdIsIgnored() {
        broadcaster.subscribe("not-a-uuid");

        verify(repository, never()).after(any(), anyInt());
        verify(repository, never()).latest(anyInt());
        assertThat(broadcaster.subscribers()).isEqualTo(1);
    }

    @Test
    void emittersViewIsReadOnlySnapshot() {
        broadcaster.subscribe(null);
        assertThat(broadcaster.emitters()).hasSize(1);
    }
}
