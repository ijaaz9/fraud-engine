package com.fraud.detection.api.dto;


import com.fraud.detection.model.enums.FlagStatus;
import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.enums.Severity;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API response DTOs.
 *
 * Keeping DTOs separate from JPA entities means the API contract can evolve
 * independently of the database schema. Clients are never exposed to JPA
 * internals (lazy-loading proxies, Hibernate annotations, etc.).
 *
 * All DTOs are immutable value objects (Lombok @Value).
 */
public class FraudFlagDto {

    // Private constructor — this is a namespace class, not meant to be instantiated.
    private FraudFlagDto() {}

    // -------------------------------------------------------------------------
    // Endpoint 1: Paginated list of flagged transactions
    // -------------------------------------------------------------------------

    /**
     * A single fraud flag entry in the paginated list response.
     */
    @Value
    @Builder
    public static class FlagSummary {
        Long flagId;
        String transactionId;
        String userId;
        BigDecimal amount;
        String merchant;
        String category;
        FraudRuleType ruleTriggered;
        Severity severity;
        Integer score;
        String detail;
        FlagStatus status;
        Instant createdAt;
    }

    /**
     * Paginated wrapper returned by GET /api/v1/flags
     */
    @Value
    @Builder
    public static class PagedFlagResponse {
        List<FlagSummary> content;
        int page;
        int size;
        long totalElements;
        int totalPages;
        boolean last;
    }

    // -------------------------------------------------------------------------
    // Endpoint 2: All flags for a specific transaction ID
    // -------------------------------------------------------------------------

    /**
     * Full detail for a single transaction including all its fraud flags.
     * Returned by GET /api/v1/flags/{transactionId}
     */
    @Value
    @Builder
    public static class TransactionFlagDetail {
        String transactionId;
        String userId;
        BigDecimal amount;
        String merchant;
        String category;
        Double latitude;
        Double longitude;
        Instant timestamp;
        Instant processedAt;

        /** Overall severity (highest across all flags for this transaction). */
        Severity overallSeverity;

        /** Sum of all triggered rule scores for this transaction. */
        Integer totalScore;

        /** One entry per fraud rule that fired. */
        List<FlagEntry> flags;
    }

    /**
     * A single flag entry within a {@link TransactionFlagDetail}.
     */
    @Value
    @Builder
    public static class FlagEntry {
        Long flagId;
        FraudRuleType ruleTriggered;
        Severity severity;
        Integer score;
        String detail;
        FlagStatus status;
        Instant createdAt;
    }

    // -------------------------------------------------------------------------
    // Endpoint 3: Summary stats by time range and category
    // -------------------------------------------------------------------------

    /**
     * Aggregated fraud statistics over a time range.
     * Returned by GET /api/v1/flags/stats
     */
    @Value
    @Builder
    public static class FraudStatsResponse {
        Instant from;
        Instant to;
        String category;
        long totalFlags;

        /**
         * Breakdown of flag counts by rule type.
         * Key: FraudRuleType name, Value: count of flags triggered by that rule.
         */
        Map<String, Long> byRule;

        /**
         * Breakdown of flag counts by severity level.
         * Key: Severity name, Value: count of flags at that severity.
         */
        Map<String, Long> bySeverity;
    }
}
