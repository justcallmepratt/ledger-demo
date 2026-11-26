package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.api.dto.Views;
import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.persistence.OutboxMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** Builds outbox rows holding CloudEvents JSON as specified in docs/API.md. */
@Component
public class EventFactory {

    public static final String SOURCE = "/ledger-core";

    private final JsonMapper mapper;

    public EventFactory(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public OutboxMessage accountCreated(Account account, Instant now) {
        return build(EventType.ACCOUNT_CREATED, account.id(), Views.account(account), now);
    }

    public OutboxMessage transfer(EventType type, Transfer transfer, Account debitAfter, Account creditAfter,
                                  Instant now) {
        TransferEventData data = new TransferEventData(Views.transfer(transfer), Views.balance(debitAfter),
                Views.balance(creditAfter));
        return build(type, transfer.id(), data, now);
    }

    private OutboxMessage build(EventType type, UUID subject, Object data, Instant now) {
        UUID eventId = UUID.randomUUID();
        CloudEvent event = new CloudEvent("1.0", eventId.toString(), SOURCE, type.cloudEventType(), now.toString(),
                subject.toString(), "application/json", data);
        return new OutboxMessage(0L, eventId, type.topic(), subject.toString(), type.cloudEventType(),
                mapper.writeValueAsString(event), now);
    }
}
