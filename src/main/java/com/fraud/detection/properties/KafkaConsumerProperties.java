package com.fraud.detection.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Strongly-typed configuration for the transaction Kafka consumer.
 *
 * Binds {@code fraud.kafka.*}, e.g.:
 * <pre>
 * fraud:
 *   kafka:
 *     bootstrap-servers: localhost:9092
 *     group-id: fraud-detection-group
 *     concurrency: 3
 * </pre>
 *
 * Uses an application-owned prefix ({@code fraud.kafka}) rather than the
 * framework-reserved {@code spring.kafka}, since this project configures the
 * consumer manually and does not include Spring Boot's Kafka auto-configuration.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fraud.kafka")
public class KafkaConsumerProperties {

    /** Comma-separated list of Kafka broker addresses (host:port). Required. */
    private String bootstrapServers;

    /** Consumer group id. */
    private String groupId = "fraud-detection-group";

    /**
     * Number of concurrent consumer threads. Should not exceed the number of
     * partitions on the transactions topic. Defaults to 3.
     */
    private int concurrency = 3;
}

