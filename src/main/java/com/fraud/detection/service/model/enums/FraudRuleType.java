package com.fraud.detection.service.model.enums;


/**
 * Canonical identifiers for each fraud rule in the system.
 * Using an enum (rather than raw strings) ensures type-safety across rule
 * evaluation results, persisted flag records, and API responses. Adding a
 * new rule requires adding a value here and providing a corresponding
 * FraudRule implementation — no other changes needed (Open/Closed Principle).
 */
public enum FraudRuleType {

    /** User exceeded the allowed transaction count within a rolling time window. */
    VELOCITY_CHECK,
    /** A single transaction amount exceeded the configured threshold. */
    HIGH_AMOUNT,
    /** Transaction originated from a location anomalous relative to recent activity. */
    GEO_ANOMALY,
    /**
     * The speed implied by the distance between two consecutive transaction locations
     * and the elapsed time between them is physically impossible.
     */
    IMPOSSIBLE_TRAVEL,
    /** A transaction with an identical amount + merchant was seen very recently for this user. */
    DUPLICATE_TRANSACTION
}
