package com.fraud.detection.consumer;

import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.service.FraudDetectionService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kafka consumer that listens to the "transactions" topic and drives the
 * fraud detection pipeline for each inbound event.
 *
 * === Reliability ===
 * @RetryableTopic configures automatic retry with exponential backoff before
 * routing failed messages to the Dead Letter Topic (DLT). This ensures:
 *   - Transient failures (DB down, Redis timeout) are retried automatically.
 *   - Poison-pill messages (malformed JSON, invalid data) don't block the
 *     partition — they are sent to the DLT after exhausting retries.
 *
 * Retry topics are auto-created with the suffix "-retry-N" and the DLT with
 * suffix "-dlt". Topic names: transactions-retry-0, transactions-retry-1,
 * transactions-retry-2, transactions-dlt.
 *
 * === Manual acknowledgement ===
 * AckMode is set to MANUAL in KafkaConfig. We only ack after successful
 * processing to prevent message loss on consumer crashes.
 *
 * === Concurrency ===
 * The concurrency level (number of consumer threads) is set in application.yml
 * under spring.kafka.listener.concurrency. Each thread processes one partition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionKafkaConsumer {

    private final FraudDetectionService fraudDetectionService;
    private final Validator validator;

    /**
     * Main listener with retry + DLT configuration.
     *
     * Retry schedule (exponential backoff):
     *   Attempt 1: immediate
     *   Attempt 2: 1 second delay
     *   Attempt 3: 2 seconds delay
     *   Attempt 4: 4 seconds delay
     *   → DLT after all retries exhausted
     */
    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = "-dlt",
            autoCreateTopics = "true"
    )
    @KafkaListener(
            topics = "${fraud.kafka.topic.transactions:transactions}",
            groupId = "${fraud.kafka.group-id:fraud-detection-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onTransaction(
            TransactionEvent event,
            Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("Received transaction [{}] from topic={}, partition={}, offset={}",
                event.getTransactionId(), topic, partition, offset);

        // Validate the deserialised event before processing
        Set<ConstraintViolation<TransactionEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String errors = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            // Throw so the retry/DLT mechanism picks it up — don't ack a bad message
            throw new IllegalArgumentException(
                    "Invalid transaction event [" + event.getTransactionId() + "]: " + errors);
        }

        fraudDetectionService.process(event);

        // Manually acknowledge only after successful processing
        acknowledgment.acknowledge();
        log.info("Transaction [{}] processed and acknowledged", event.getTransactionId());
    }

    /**
     * Dead Letter Topic handler — receives messages that failed all retry attempts.
     *
     * In production this would trigger an alert (PagerDuty, Slack, etc.).
     * Here we log at ERROR level so monitoring systems can pick it up.
     */
    @KafkaListener(
            topics = "${fraud.kafka.topic.transactions:transactions}-dlt",
            groupId = "${fraud.kafka.group-id:fraud-detection-group}-dlt"
    )
    public void onDeadLetter(
            ConsumerRecord<String, String> record,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {
        log.error("Dead letter received on topic [{}] — key=[{}], payload=[{}]",
                topic, record.key(), record.value());
        // TODO: persist to a dead_letter_events table or trigger an alert
    }
}
