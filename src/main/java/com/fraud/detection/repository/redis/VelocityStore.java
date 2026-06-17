package com.fraud.detection.repository.redis;


/**
 * Port for recording and querying transaction velocity per user.
 *
 * === Interface Segregation ===
 * Rather than a single "RedisStore" interface with all operations,
 * each rule gets only the interface it needs. VelocityRule depends on
 * this; DuplicateTransactionRule depends on DeduplicationStore.
 * Neither sees methods they don't use.
 *
 * === Dependency Inversion ===
 * Rules depend on this abstraction. The Redis implementation is injected
 * at runtime by Spring. Tests can inject an in-memory stub.
 */
public interface VelocityStore {

    /**
     * Records the given timestamp for the user and returns the number of
     * transactions that fall within the rolling window ending at that timestamp.
     *
     * Implementations must atomically:
     *   1. Add the timestamp to the user's sorted set.
     *   2. Remove entries older than (timestampMillis - windowSeconds * 1000).
     *   3. Return the count of remaining entries.
     *
     * @param userId          the user whose velocity is being tracked
     * @param timestampMillis the transaction timestamp as epoch millis
     * @param windowSeconds   the rolling window duration in seconds
     * @return the number of transactions (including this one) in the window
     */
    long recordAndCount(String userId, long timestampMillis, long windowSeconds);
}
