package com.fraud.detection.repository.redis;

/**
 * Port for tracking which transaction IDs have already been processed.
 *
 * Kafka provides at-least-once delivery guarantees, meaning the same message
 * may be delivered more than once (e.g. after a consumer crash and rebalance).
 * This store ensures each unique transactionId is processed exactly once by
 * setting a short-lived Redis key on first processing and checking for it on
 * subsequent deliveries.
 */
public interface IdempotencyStore {

    /**
     * Atomically checks whether a transaction has already been processed and,
     * if not, marks it as processed.
     *
     * Uses Redis SET NX (set if not exists) with TTL for atomicity — no
     * separate check-then-set race condition.
     *
     * @param transactionId the unique transaction identifier
     * @param ttlHours      how long to retain the processed marker
     * @return true if this transactionId was NOT previously seen (i.e. should
     *         be processed), false if it was already processed (skip it)
     */
    boolean isNewAndMark(String transactionId, long ttlHours);
}
