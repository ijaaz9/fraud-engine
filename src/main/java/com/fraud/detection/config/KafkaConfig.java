package com.fraud.detection.config;

import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.properties.KafkaConsumerProperties;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.retry.annotation.EnableRetry;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration.
 *
 * Key decisions:
 *
 * 1. MANUAL_IMMEDIATE acknowledgement — offsets are committed immediately after
 *    Acknowledgment.acknowledge() is called, rather than on the next poll cycle.
 *    This is required by @RetryableTopic: using AckMode.MANUAL causes the retry
 *    topic routing to conflict with the offset commit timing, leading to missed
 *    retries or duplicate DLT entries. MANUAL_IMMEDIATE avoids this.
 *
 * 2. ErrorHandlingDeserializer — wraps the JSON deserializer so that messages
 *    that cannot be deserialised (corrupted bytes, schema changes) are routed
 *    to the DLT rather than blocking the partition with a fatal exception.
 *
 * 3. Concurrency — controlled via application.yml so it can be tuned without
 *    a code change. Should not exceed the number of topic partitions.
 *
 * 4. @EnableRetry — required for @RetryableTopic to activate retry behaviour.
 *    Without it the annotation is silently ignored and failed messages go
 *    straight to the DLT after one attempt.
 */
@EnableKafka
@EnableRetry
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private final KafkaConsumerProperties properties;

    @Bean
    public ConsumerFactory<String, TransactionEvent> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getGroupId());

        // Start from the earliest unread offset when the group has no committed offset.
        // In production you may prefer "latest" if you only care about new messages.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Disable auto-commit — we manage offsets manually via Acknowledgment
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Tune fetch size for throughput (fetch up to 500 records per poll)
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);

        // ErrorHandlingDeserializer ensures malformed messages go to DLT,
        // not into an infinite error loop blocking the partition
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);
        props.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, TransactionEvent.class.getName());
        // Wildcard avoids the brittle hardcoded package string breaking on rename.
        // Acceptable for a demo; in production scope this to the specific package.
        props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "*");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, TransactionEvent> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);

        // MANUAL_IMMEDIATE: offsets committed immediately when acknowledge() is called.
        // Required by @RetryableTopic — AckMode.MANUAL conflicts with retry topic routing.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Number of concurrent consumer threads — should match partition count
        factory.setConcurrency(properties.getConcurrency());

        return factory;
    }

    // ── Producer side ───────────────────────────────────────────────────────────
    // Required by @RetryableTopic: Spring Kafka uses a KafkaTemplate to forward
    // failed records to the retry and dead-letter topics. This project does not
    // include Spring Boot's Kafka auto-configuration, so the producer factory,
    // template, and admin are defined explicitly here.

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * KafkaAdmin enables @RetryableTopic's autoCreateTopics to provision the
     * retry and dead-letter topics on startup.
     */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        return new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers()));
    }

}
