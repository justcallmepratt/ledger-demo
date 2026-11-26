package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import dev.justcallmepratt.ledger.persistence.ActivityEntry;
import dev.justcallmepratt.ledger.persistence.ActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static dev.justcallmepratt.ledger.events.EventFactoryTest.MAPPER;
import static dev.justcallmepratt.ledger.events.EventFactoryTest.NOW;
import static dev.justcallmepratt.ledger.events.EventFactoryTest.account;
import static dev.justcallmepratt.ledger.events.EventFactoryTest.stored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityProjectorTest {

    private final ActivityRepository repository = mock(ActivityRepository.class);
    private final ActivityBroadcaster broadcaster = mock(ActivityBroadcaster.class);
    private final EventFactory factory = new EventFactory(MAPPER);
    private ActivityProjector projector;

    @BeforeEach
    void setUp() {
        projector = new ActivityProjector(MAPPER, repository, broadcaster, 1000);
        when(repository.insertIfAbsent(any())).thenReturn(true);
    }

    private ActivityEntry projectTransfer(EventType type, TransferFlags flags, PendingStatus status) {
        Account d = account("d");
        Account c = account("c");
        Transfer t = stored(d, c, 1050, flags, status);
        assertThat(projector.handle(factory.transfer(type, t, d, c, NOW).payload())).isTrue();
        ArgumentCaptor<ActivityEntry> captor = ArgumentCaptor.forClass(ActivityEntry.class);
        verify(repository).insertIfAbsent(captor.capture());
        return captor.getValue();
    }

    @Test
    void projectsATransferCreatedEvent() {
        ActivityEntry e = projectTransfer(EventType.TRANSFER_CREATED, TransferFlags.NONE, null);

        assertThat(e.type()).isEqualTo("transfer.created");
        assertThat(e.seq()).isEqualTo(42L);
        assertThat(e.amount()).isEqualTo(1050L);
        assertThat(e.ledger()).isEqualTo(840);
        assertThat(e.occurredAt()).isEqualTo(NOW);
        assertThat(e.accountId()).isNull();
        assertThat(e.transferId()).isNotNull();
        assertThat(e.summary()).isEqualTo("Transfer created: 1050 (ledger 840)");
        verify(repository).prune(1000);
        verify(broadcaster).publish(e);
    }

    @Test
    void describesPendingPostedVoidedAndExpired() {
        assertThat(projectTransfer(EventType.TRANSFER_CREATED, TransferFlags.PENDING, PendingStatus.PENDING)
                .summary()).startsWith("Pending transfer created");
    }

    @Test
    void postedVoidedExpiredSummaries() {
        for (String[] pair : new String[][]{{"POSTED", "Pending transfer posted"}, {"VOIDED", "Pending transfer voided"},
                {"EXPIRED", "Pending transfer expired"}}) {
            ActivityRepository repo = mock(ActivityRepository.class);
            when(repo.insertIfAbsent(any())).thenReturn(true);
            ActivityProjector p = new ActivityProjector(MAPPER, repo, broadcaster, 10);
            EventType type = EventType.valueOf("TRANSFER_" + pair[0]);
            Account d = account("d");
            Account c = account("c");
            Transfer t = stored(d, c, 7, TransferFlags.NONE, null);
            p.handle(factory.transfer(type, t, d, c, NOW).payload());
            ArgumentCaptor<ActivityEntry> captor = ArgumentCaptor.forClass(ActivityEntry.class);
            verify(repo).insertIfAbsent(captor.capture());
            assertThat(captor.getValue().summary()).startsWith(pair[1]);
        }
    }

    @Test
    void projectsAnAccountCreatedEvent() {
        Account a = account("Alice wallet");

        assertThat(projector.handle(factory.accountCreated(a, NOW).payload())).isTrue();

        ArgumentCaptor<ActivityEntry> captor = ArgumentCaptor.forClass(ActivityEntry.class);
        verify(repository).insertIfAbsent(captor.capture());
        ActivityEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("account.created");
        assertThat(e.accountId()).isEqualTo(a.id());
        assertThat(e.seq()).isNull();
        assertThat(e.amount()).isNull();
        assertThat(e.transferId()).isNull();
        assertThat(e.summary()).isEqualTo("Account created: Alice wallet");
    }

    @Test
    void duplicatesAreIgnoredAndNotBroadcast() {
        when(repository.insertIfAbsent(any())).thenReturn(false);
        Account a = account("x");

        assertThat(projector.handle(factory.accountCreated(a, NOW).payload())).isFalse();

        verify(repository, never()).prune(anyInt());
        verify(broadcaster, never()).publish(any());
    }

    @Test
    void foreignEventTypesAreIgnored() {
        String payload = "{\"specversion\":\"1.0\",\"id\":\"" + UUID.randomUUID() + "\",\"source\":\"x\","
                + "\"type\":\"com.other.thing.v1\",\"time\":\"2026-11-26T08:00:00Z\",\"subject\":\"s\","
                + "\"datacontenttype\":\"application/json\",\"data\":{}}";

        assertThat(projector.handle(payload)).isFalse();

        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    void onMessageDelegatesToHandle() {
        projector.onMessage(factory.accountCreated(account("y"), NOW).payload());

        verify(repository).insertIfAbsent(any());
    }

    @Test
    void shortTypeRecognisesOnlyOurEvents() {
        assertThat(ActivityProjector.shortType("dev.justcallmepratt.ledger.transfer.created.v1"))
                .isEqualTo("transfer.created");
        assertThat(ActivityProjector.shortType("dev.justcallmepratt.ledger.transfer.unknown.v1")).isNull();
        assertThat(ActivityProjector.shortType("dev.justcallmepratt.ledger.transfer.created.v2")).isNull();
        assertThat(ActivityProjector.shortType("other.transfer.created.v1")).isNull();
        assertThat(ActivityProjector.shortType(null)).isNull();
    }
}
