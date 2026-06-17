package com.fraud.detection.repository.redis;


/**
 * Port for detecting and recording duplicate transactions within a time window.
 *
 * See {@link VelocityStore} for the Interface Segregation rationale.
 */
public interface DeduplicationStore {

    /**
     * Checks whether an identical transaction (same user, merchant, amount)
     * has been seen within the configured window, then records this one.
     *
     * The check-then-record operation should be treated as best-effort atomic:
     * in practice, a tiny race window may exist between check and write, but
     * the business cost of a missed duplicate is low relative to the cost of
     * complex distributed locking.
     *
     * @param userId        the user initiating the transaction
     * @param merchant      the merchant identifier
     * @param amount        the transaction amount as a normalised string
     * @param windowSeconds the window within which a repeat is considered a duplicate
     * @return true if an identical transaction was already recorded in the window
     */
    boolean isDuplicateAndRecord(String userId, String merchant, String amount, long windowSeconds);
}
