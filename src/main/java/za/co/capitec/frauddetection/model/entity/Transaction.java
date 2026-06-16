package za.co.capitec.frauddetection.model.entity;


import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Externally assigned transaction identifier (from the source system).
     * Used as the idempotency key to prevent double-processing of retried
     * Kafka messages.
     */
    @Column(name = "transaction_id", nullable = false, unique = true, length = 128)
    private String transactionId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 256)
    private String merchant;

    @Column(nullable = false, length = 128)
    private String category;

    /** Decimal degrees latitude of the transaction origin. */
    @Column(nullable = false)
    private Double latitude;

    /** Decimal degrees longitude of the transaction origin. */
    @Column(nullable = false)
    private Double longitude;

    /** When the transaction actually occurred (from the source event). */
    @Column(nullable = false)
    private Instant timestamp;

    /** When this record was written to the database. */
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    /**
     * All fraud flags raised against this transaction.
     * CascadeType.ALL ensures flags are removed if the transaction is deleted
     * (rare in production, but maintains referential consistency).
     */
    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<FraudFlag> fraudFlags = new ArrayList<>();

    @PrePersist
    private void onPersist() {
        this.processedAt = Instant.now();
    }
}
