package dev.justcallmepratt.ledger.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares the topics; Spring's KafkaAdmin creates them at startup if missing. */
@Configuration
@ConditionalOnProperty(name = "ledger.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTopicsConfig {

    @Bean
    public NewTopic transfersTopic(@Value("${ledger.kafka.partitions:3}") int partitions,
                                   @Value("${ledger.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.TRANSFERS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic accountsTopic(@Value("${ledger.kafka.partitions:3}") int partitions,
                                  @Value("${ledger.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.ACCOUNTS).partitions(partitions).replicas(replicas).build();
    }
}
