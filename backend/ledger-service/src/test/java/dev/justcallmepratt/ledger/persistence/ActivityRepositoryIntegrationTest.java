package dev.justcallmepratt.ledger.persistence;

import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired ActivityRepository repository;
    @Autowired OutboxRepository outbox;
    @Autowired LedgerRepository ledgers;

    private static ActivityEntry entry(int n) {
        return new ActivityEntry(UUID.randomUUID(), "transfer.created", (long) n, UUID.randomUUID(), null, 100L + n,
                840, Instant.parse("2026-11-26T08:00:00Z"), "entry " + n);
    }

    @Test
    void insertDedupesOnEventId() {
        ActivityEntry e = entry(1);

        assertThat(repository.insertIfAbsent(e)).isTrue();
        assertThat(repository.insertIfAbsent(e)).isFalse();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void latestIsNewestFirstAndNullsRoundTrip() {
        ActivityEntry account = new ActivityEntry(UUID.randomUUID(), "account.created", null, null, UUID.randomUUID(),
                null, 840, Instant.parse("2026-11-26T08:00:00Z"), "Account created: x");
        repository.insertIfAbsent(account);
        repository.insertIfAbsent(entry(2));

        List<ActivityEntry> latest = repository.latest(10);

        assertThat(latest).hasSize(2);
        assertThat(latest.get(0).type()).isEqualTo("transfer.created");
        assertThat(latest.get(1)).isEqualTo(account);
        assertThat(latest.get(1).seq()).isNull();
        assertThat(latest.get(1).amount()).isNull();
    }

    @Test
    void afterReplaysOldestFirstAndKnowsUnknownIds() {
        ActivityEntry first = entry(1);
        ActivityEntry second = entry(2);
        ActivityEntry third = entry(3);
        repository.insertIfAbsent(first);
        repository.insertIfAbsent(second);
        repository.insertIfAbsent(third);

        assertThat(repository.after(first.eventId(), 10)).hasValueSatisfying(
                list -> assertThat(list).extracting(ActivityEntry::eventId)
                        .containsExactly(second.eventId(), third.eventId()));
        assertThat(repository.after(third.eventId(), 10)).hasValue(List.of());
        assertThat(repository.after(UUID.randomUUID(), 10)).isEmpty();
    }

    @Test
    void pruneKeepsTheNewestRows() {
        for (int i = 1; i <= 10; i++) {
            repository.insertIfAbsent(entry(i));
        }

        repository.prune(4);

        assertThat(repository.count()).isEqualTo(4);
        assertThat(repository.latest(10)).extracting(ActivityEntry::seq).containsExactly(10L, 9L, 8L, 7L);
        repository.prune(100);
        assertThat(repository.count()).isEqualTo(4);
    }

    @Test
    void outboxLockingSkipsLockedRowsAndTracksPublication() {
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID();
            outbox.insert(new OutboxMessage(0, id, "ledger.transfers.v1", id.toString(), "t", "{\"a\":" + i + "}",
                    Instant.parse("2026-11-26T08:00:00Z")));
        }
        assertThat(outbox.countUnpublished()).isEqualTo(3);

        List<OutboxMessage> batch = outbox.lockUnpublished(2);
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).payload()).contains("\"a\"");
        outbox.markPublished(batch.get(0).id(), Instant.parse("2026-11-26T08:00:01Z"));
        outbox.markAttemptFailed(batch.get(1).id());

        assertThat(outbox.countUnpublished()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT attempts FROM outbox WHERE id = :id").param("id", batch.get(1).id())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void ledgerLookups() {
        assertThat(ledgers.exists(840)).isTrue();
        assertThat(ledgers.exists(1)).isFalse();
        assertThat(ledgers.findAll()).anySatisfy(l -> {
            assertThat(l.id()).isEqualTo(840);
            assertThat(l.currency()).isEqualTo("USD");
            assertThat(l.assetScale()).isEqualTo(2);
        });
    }
}
