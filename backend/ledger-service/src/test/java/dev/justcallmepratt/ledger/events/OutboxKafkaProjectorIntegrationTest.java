package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.persistence.ActivityRepository;
import dev.justcallmepratt.ledger.persistence.OutboxRepository;
import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transfer -> outbox row (same transaction) -> OutboxPublisher -> embedded Kafka (KRaft) ->
 * ActivityProjector -> activity table, with the real scheduled publisher and listener running.
 */
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "ledger.scheduling.enabled=true",
        "ledger.kafka.enabled=true",
        "ledger.outbox.poll-interval-ms=100",
        "ledger.reconciliation.initial-delay-ms=3600000",
        "ledger.expiry.interval-ms=3600000"
})
class OutboxKafkaProjectorIntegrationTest extends IntegrationTestBase {

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired OutboxRepository outbox;
    @Autowired ActivityRepository activity;
    @Autowired OutboxPublisher publisher;
    @Autowired ActivityProjector projector;

    @Test
    void aTransferFlowsFromTheOutboxThroughKafkaIntoTheActivityFeed() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        Transfer t = single(a, b, 1050);
        transferService.submit(t);

        // 2 account.created + 1 transfer.created reach the activity table
        assertThat(waitFor(() -> activity.count() >= 3, 60_000)).as("activity rows projected").isTrue();
        assertThat(waitFor(() -> outbox.countUnpublished() == 0, 30_000)).isTrue();

        assertThat(activity.latest(10)).extracting(e -> e.type())
                .contains("account.created", "transfer.created");
        assertThat(activity.latest(10)).anySatisfy(e -> {
            assertThat(e.transferId()).isEqualTo(t.id());
            assertThat(e.amount()).isEqualTo(1050L);
            assertThat(e.ledger()).isEqualTo(840);
            assertThat(e.summary()).contains("1050");
        });
        mockMvc.perform(get("/api/v1/activity").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='transfer.created')].amount").value("1050"));
    }

    @Test
    void theKafkaMessageIsAStructuredCloudEventKeyedByTransferId() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        Transfer t = single(a, b, 77);
        transferService.submit(t);

        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", broker.getBrokersAsString());
        props.put("group.id", "test-reader-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class);
        props.put("value.deserializer", StringDeserializer.class);
        ConsumerRecord<String, String> found = null;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(Topics.TRANSFERS));
            long deadline = System.currentTimeMillis() + 60_000;
            while (found == null && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (t.id().toString().equals(r.key())) {
                        found = r;
                    }
                }
            }
        }

        assertThat(found).as("record for the transfer").isNotNull();
        assertThat(new String(found.headers().lastHeader("content-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("application/cloudevents+json");
        assertThat(found.value()).contains("\"specversion\":\"1.0\"")
                .contains("\"type\":\"dev.justcallmepratt.ledger.transfer.created.v1\"")
                .contains("\"source\":\"/ledger-core\"")
                .contains("\"subject\":\"" + t.id() + "\"")
                .contains("\"amount\":\"77\"")
                .contains("\"debitAccount\"")
                .contains("\"creditAccount\"");
    }

    @Test
    void redeliveredEventsAreIgnored() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        transferService.submit(single(a, b, 5));
        UUID eventId = jdbc.sql("SELECT event_id FROM outbox WHERE event_type LIKE '%transfer.created%'")
                .query(UUID.class).single();
        assertThat(waitFor(() -> projected(eventId) == 1, 60_000)).isTrue();
        String payload = jdbc.sql("SELECT CAST(payload AS text) FROM outbox WHERE event_id = :id")
                .param("id", eventId).query(String.class).single();

        assertThat(projector.handle(payload)).isFalse();
        assertThat(projected(eventId)).isEqualTo(1);
        // publishing again with nothing pending is a no-op
        assertThat(waitFor(() -> outbox.countUnpublished() == 0, 30_000)).isTrue();
        assertThat(publisher.publishBatch()).isZero();
    }

    private long projected(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM activity WHERE event_id = :id").param("id", eventId)
                .query(Long.class).single();
    }
}
