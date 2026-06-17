package com.fraud.detection.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.fraud.detection.model.enums.FlagStatus;
import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.enums.Severity;

import java.time.Instant;

/**
 * Records a single fraud rule violation against a transaction.
 *
 * One FraudFlag is created per rule that fires. If three rules fire on a
 * single transaction, three FraudFlag rows are inserted. This design allows
 * analysts to understand exactly which rules triggered and why, rather than
 * receiving only a summary score.
 *
 * The {@code severity} on each flag reflects the overall transaction severity
 * (sum of all rule scores) — consistent across all flags for the same txn.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table( name = "fraud_flags")
public class FraudFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The transaction this flag belongs to.
     * Stored as a FK; LAZY loaded since we don't always need the full txn.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /** Which fraud rule triggered this flag. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_triggered", nullable = false, length = 64)
    private FraudRuleType ruleTriggered;

    /**
     * Overall severity derived from the aggregate score of ALL rules that
     * fired on this transaction — not just this individual rule's score.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    /**
     * The aggregate fraud score across all triggered rules for this txn.
     * Stored here for quick sorting/filtering without joining back to compute.
     */
    @Column(nullable = false)
    private Integer score;

    /**
     * Human-readable explanation of why this specific rule fired.
     * E.g. "12 transactions in the last 5 minutes (threshold: 10)"
     */
    @Column(nullable = false, length = 512)
    private String detail;

    /** Lifecycle status — OPEN until an analyst marks it REVIEWED. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private FlagStatus status = FlagStatus.OPEN;

    /** When this flag was created. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onPersist() {
        this.createdAt = Instant.now();
    }
}
