package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.api.dto.AccountResponse;
import dev.justcallmepratt.ledger.api.dto.TransferResponse;
import dev.justcallmepratt.ledger.persistence.ActivityEntry;
import dev.justcallmepratt.ledger.persistence.ActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes both topics in its own consumer group and projects them into the bounded
 * {@code activity} table, then pushes new entries to SSE clients. Redelivered events are
 * ignored: the CloudEvents id is the primary dedupe key.
 */
@Component
@ConditionalOnProperty(name = "ledger.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ActivityProjector {

    public static final String GROUP_ID = "ledger-activity-projector";
    private static final Logger log = LoggerFactory.getLogger(ActivityProjector.class);

    /** Inbound CloudEvent; {@code data} is decoded according to {@code type}. */
    public record InboundEvent(String specversion, String id, String source, String type, String time, String subject,
                        String datacontenttype, Map<String, Object> data) {
    }

    private final JsonMapper mapper;
    private final ActivityRepository repository;
    private final ActivityBroadcaster broadcaster;
    private final int keep;

    public ActivityProjector(JsonMapper mapper, ActivityRepository repository, ActivityBroadcaster broadcaster,
                             @Value("${ledger.activity.keep:1000}") int keep) {
        this.mapper = mapper;
        this.repository = repository;
        this.broadcaster = broadcaster;
        this.keep = keep;
    }

    @KafkaListener(id = GROUP_ID, topics = {Topics.TRANSFERS, Topics.ACCOUNTS}, groupId = GROUP_ID)
    public void onMessage(String payload) {
        handle(payload);
    }

    /** Projects one CloudEvent. Returns false if it was a duplicate or of an unknown type. */
    public boolean handle(String payload) {
        InboundEvent event = mapper.readValue(payload, InboundEvent.class);
        String type = shortType(event.type());
        if (type == null) {
            log.warn("Ignoring event {} of unknown type {}", event.id(), event.type());
            return false;
        }
        ActivityEntry entry = toEntry(event, type);
        if (!repository.insertIfAbsent(entry)) {
            return false;
        }
        repository.prune(keep);
        broadcaster.publish(entry);
        return true;
    }

    private ActivityEntry toEntry(InboundEvent event, String type) {
        UUID eventId = UUID.fromString(event.id());
        Instant time = Instant.parse(event.time());
        if (type.equals(EventType.ACCOUNT_CREATED.shortName())) {
            AccountResponse account = mapper.convertValue(event.data(), AccountResponse.class);
            return new ActivityEntry(eventId, type, null, null, account.id(), null, account.ledger(), time,
                    "Account created: " + abbreviate(account.name(), 100));
        }
        TransferEventData data = mapper.convertValue(event.data(), TransferEventData.class);
        TransferResponse t = data.transfer();
        String what = switch (type) {
            case "transfer.posted" -> "Pending transfer posted";
            case "transfer.voided" -> "Pending transfer voided";
            case "transfer.expired" -> "Pending transfer expired";
            default -> t.flags().pending() ? "Pending transfer created" : "Transfer created";
        };
        return new ActivityEntry(eventId, type, Long.parseLong(t.seq()), t.id(), null, Long.parseLong(t.amount()),
                t.ledger(), time, what + ": " + t.amount() + " (ledger " + t.ledger() + ")");
    }

    /** {@code dev.justcallmepratt.ledger.transfer.created.v1} to {@code transfer.created}; null if foreign. */
    static String shortType(String cloudEventType) {
        if (cloudEventType == null || !cloudEventType.startsWith(EventType.TYPE_PREFIX)
                || !cloudEventType.endsWith(EventType.TYPE_SUFFIX)) {
            return null;
        }
        String shortName = cloudEventType.substring(EventType.TYPE_PREFIX.length(),
                cloudEventType.length() - EventType.TYPE_SUFFIX.length());
        for (EventType known : EventType.values()) {
            if (known.shortName().equals(shortName)) {
                return shortName;
            }
        }
        return null;
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
