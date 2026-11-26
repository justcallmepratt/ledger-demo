package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import dev.justcallmepratt.ledger.persistence.OutboxMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventFactoryTest {

    static final Instant NOW = Instant.parse("2026-11-26T08:00:00Z");
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final EventFactory factory = new EventFactory(MAPPER);

    static Account account(String name) {
        return Account.open(UUID.randomUUID(), 840, 1, name, AccountFlags.NONE, NOW);
    }

    static Transfer stored(Account d, Account c, long amount, TransferFlags flags, PendingStatus status) {
        return new Transfer(UUID.randomUUID(), d.id(), c.id(), amount, 840, 1, flags, null, 0, "INV-1", 42L, NOW,
                status, null);
    }

    @Test
    void accountCreatedEventCarriesTheAccount() {
        Account a = account("Alice wallet");

        OutboxMessage m = factory.accountCreated(a, NOW);

        assertThat(m.topic()).isEqualTo(Topics.ACCOUNTS);
        assertThat(m.key()).isEqualTo(a.id().toString());
        assertThat(m.type()).isEqualTo("dev.justcallmepratt.ledger.account.created.v1");
        assertThat(m.createdAt()).isEqualTo(NOW);
        assertThat(m.payload()).contains("\"specversion\":\"1.0\"")
                .contains("\"id\":\"" + m.eventId() + "\"")
                .contains("\"source\":\"/ledger-core\"")
                .contains("\"time\":\"2026-11-26T08:00:00Z\"")
                .contains("\"subject\":\"" + a.id() + "\"")
                .contains("\"datacontenttype\":\"application/json\"")
                .contains("\"name\":\"Alice wallet\"")
                .contains("\"debitsPosted\":\"0\"");
    }

    @Test
    void transferEventCarriesTransferAndBothBalances() {
        Account d = account("d").withCounters(0, 1050, 0, 0);
        Account c = account("c").withCounters(0, 0, 0, 1050);
        Transfer t = stored(d, c, 1050, TransferFlags.NONE, null);

        OutboxMessage m = factory.transfer(EventType.TRANSFER_CREATED, t, d, c, NOW);

        assertThat(m.topic()).isEqualTo(Topics.TRANSFERS);
        assertThat(m.key()).isEqualTo(t.id().toString());
        assertThat(m.type()).isEqualTo("dev.justcallmepratt.ledger.transfer.created.v1");
        assertThat(m.payload()).contains("\"amount\":\"1050\"")
                .contains("\"seq\":\"42\"")
                .contains("\"reference\":\"INV-1\"")
                .contains("\"debitAccount\":{\"id\":\"" + d.id() + "\"")
                .contains("\"creditsPosted\":\"1050\"");
    }

    @Test
    void roundTripsThroughTheProjectorsEnvelope() {
        Account d = account("d");
        Account c = account("c");
        Transfer t = stored(d, c, 5, TransferFlags.PENDING, PendingStatus.PENDING);

        OutboxMessage m = factory.transfer(EventType.TRANSFER_CREATED, t, d, c, NOW);
        ActivityProjector.InboundEvent in = MAPPER.readValue(m.payload(), ActivityProjector.InboundEvent.class);

        assertThat(in.id()).isEqualTo(m.eventId().toString());
        assertThat(in.type()).isEqualTo(EventType.TRANSFER_CREATED.cloudEventType());
        assertThat(in.subject()).isEqualTo(t.id().toString());
        TransferEventData data = MAPPER.convertValue(in.data(), TransferEventData.class);
        assertThat(data.transfer().pendingStatus()).isEqualTo("PENDING");
        assertThat(data.transfer().timestamp()).isEqualTo(NOW);
    }

    @Test
    void eventTypes() {
        assertThat(EventType.TRANSFER_POSTED.cloudEventType()).isEqualTo("dev.justcallmepratt.ledger.transfer.posted.v1");
        assertThat(EventType.TRANSFER_VOIDED.shortName()).isEqualTo("transfer.voided");
        assertThat(EventType.TRANSFER_EXPIRED.topic()).isEqualTo("ledger.transfers.v1");
        assertThat(EventType.ACCOUNT_CREATED.topic()).isEqualTo("ledger.accounts.v1");
    }
}
