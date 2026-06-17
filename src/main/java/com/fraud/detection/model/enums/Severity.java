package com.fraud.detection.model.enums;

/**
 * Represents the overall fraud severity level assigned to a transaction
 * after all applicable rules have been evaluated and scores aggregated.
 *
 * Score bands:
 *   0       → NONE     (clean transaction, no fraud flags persisted)
 *   1–30    → LOW
 *   31–55   → MEDIUM
 *   56–80   → HIGH
 *   81+     → CRITICAL
 */
public enum Severity {

    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /**
     * Derives a Severity level from a raw aggregate score.
     *
     * @param score the sum of all triggered rule scores for a transaction
     * @return the corresponding Severity level
     */
    public static Severity fromScore(int score) {

        if (score <= 0)  return NONE;
        if (score <= 30) return LOW;
        if (score <= 55) return MEDIUM;
        if (score <= 80) return HIGH;
        return CRITICAL;
    }
}
