package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.api.dto.Views;
import dev.justcallmepratt.ledger.persistence.ActivityEntry;
import dev.justcallmepratt.ledger.persistence.ActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Fans activity entries out to connected SSE clients, sends heartbeat comments and replays
 * missed entries from the {@code activity} table when a client reconnects with Last-Event-ID.
 */
@Component
public class ActivityBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ActivityBroadcaster.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final int REPLAY_LIMIT = 500;
    private static final int FALLBACK_REPLAY = 50;

    private final ActivityRepository repository;
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    public ActivityBroadcaster(ActivityRepository repository) {
        this.repository = repository;
    }

    /** Opens a stream. With a Last-Event-ID, entries projected after it are replayed first. */
    public SseEmitter subscribe(String lastEventId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        if (lastEventId != null && !lastEventId.isBlank()) {
            for (ActivityEntry entry : replayFor(lastEventId.trim())) {
                if (!send(emitter, entry)) {
                    break;
                }
            }
        }
        return emitter;
    }

    private List<ActivityEntry> replayFor(String lastEventId) {
        UUID id;
        try {
            id = UUID.fromString(lastEventId);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        Optional<List<ActivityEntry>> missed = repository.after(id, REPLAY_LIMIT);
        if (missed.isPresent()) {
            return missed.get();
        }
        // The id is unknown (pruned): fall back to the most recent entries, oldest first.
        List<ActivityEntry> latest = repository.latest(FALLBACK_REPLAY);
        return latest.reversed();
    }

    public void publish(ActivityEntry entry) {
        for (SseEmitter emitter : emitters) {
            send(emitter, entry);
        }
    }

    public int subscribers() {
        return emitters.size();
    }

    @Scheduled(fixedRateString = "${ledger.sse.heartbeat-ms:15000}")
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                }
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    private boolean send(SseEmitter emitter, ActivityEntry entry) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name("activity")
                        .id(entry.eventId().toString())
                        .data(Views.activity(entry), MediaType.APPLICATION_JSON));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping SSE client: {}", e.toString());
            emitters.remove(emitter);
            return false;
        }
    }

    /** For tests: all current subscribers. */
    Set<SseEmitter> emitters() {
        return Collections.unmodifiableSet(emitters);
    }
}
