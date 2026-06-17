package com.fraud.detection.repository.redis;

import java.util.Optional;

/**
 * Port for storing and retrieving a user's last known transaction location.
 *
 * See {@link VelocityStore} for the Interface Segregation rationale.
 */
public interface GeoLocationStore {

    /**
     * Retrieves the most recently stored location and timestamp for the given user.
     *
     * @param userId the user to look up
     * @return an Optional containing [latitude, longitude, timestampMillis] if a
     *         location is on record, or empty if no prior transaction has been seen
     */
    Optional<double[]> getLastLocation(String userId);

    /**
     * Stores the user's current location and transaction timestamp, overwriting
     * any previously stored value. The timestamp is required by the
     * {@link com.fraud.detection.rules.impl.ImpossibleTravelRule} to compute
     * the speed between consecutive transactions.
     *
     * @param userId           the user whose location is being recorded
     * @param latitude         decimal degrees latitude
     * @param longitude        decimal degrees longitude
     * @param timestampMillis  epoch millis of the transaction
     * @param ttlHours         how long (in hours) to retain this location record
     */
    void updateLocation(String userId, double latitude, double longitude,
                        long timestampMillis, long ttlHours);
}
