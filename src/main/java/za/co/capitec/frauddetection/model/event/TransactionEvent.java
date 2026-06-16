package za.co.capitec.frauddetection.model.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents the raw transaction event as consumed from the Kafka topic.
 *
 * This is the inbound DTO — it is validated on receipt and then mapped to
 * the {@link za.co.capitec.frauddetection.model.entity.Transaction} JPA entity for
 * persistence. Keeping the event model separate from the entity ensures
 * that changes to the Kafka schema do not force changes to the database
 * schema and vice versa (Single Responsibility Principle).
 *
 * Expected Kafka message format (JSON):
 * {
 *   "transactionId": "txn-abc-123",
 *   "userId":        "user-456",
 *   "amount":        1500.00,
 *   "merchant":      "ACME Corp",
 *   "category":      "ELECTRONICS",
 *   "latitude":      51.5074,
 *   "longitude":     -0.1278,
 *   "timestamp":     "2024-03-15T10:30:00Z"
 * }
 */
@Builder
public record TransactionEvent(

        @NotBlank(message = "transactionId must not be blank")
        String transactionId,

        /** The user who initiated the transaction. */
        @NotBlank(message = "userId must not be blank") 
        String userId,

        /** Transaction amount in the merchant's currency. */
        @NotNull(message = "amount must not be null")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be positive")
        BigDecimal amount,

        /** Name or identifier of the merchant. */
        @NotBlank(message = "merchant must not be blank")
        String merchant,

        /** Business category of the merchant (e.g. ELECTRONICS, TRAVEL). */
        @NotBlank(message = "category must not be blank")
        String category,

        /** Geographic latitude of the transaction origin. */
        @NotNull(message = "latitude must not be null")
        Double latitude,

        /** Geographic longitude of the transaction origin. */
        @NotNull(message = "longitude must not be null")
        Double longitude,
        
        /** When the transaction occurred, in UTC. */
        @NotNull(message = "timestamp must not be null")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant timestamp
) { }
