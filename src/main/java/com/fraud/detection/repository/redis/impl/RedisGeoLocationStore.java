package com.fraud.detection.repository.redis.impl;

import com.fraud.detection.repository.redis.GeoLocationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisGeoLocationStore implements GeoLocationStore {

    private static final String KEY_PREFIX = "geo:";
    private static final String DELIMITER = "|";
    private static final int EXPECTED_PARTS = 3;

    private final StringRedisTemplate redisTemplate;

    /**
     * Returns [latitude, longitude, timestampMillis] for the user's last
     * recorded transaction, or empty if no prior transaction exists.
     */
    @Override
    public Optional<double[]> getLastLocation(String userId) {
        String key = KEY_PREFIX + userId;
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return Optional.empty();
        }

        try {
            String[] parts = value.split("\\|");
            if (parts.length != EXPECTED_PARTS) {
                log.warn("Unexpected geo value format for key [{}]: '{}' — treating as absent", key, value);
                return Optional.empty();
            }
            double lat               = Double.parseDouble(parts[0]);
            double lon               = Double.parseDouble(parts[1]);
            double timestampMillis   = Double.parseDouble(parts[2]);
            return Optional.of(new double[]{lat, lon, timestampMillis});
        } catch (NumberFormatException ex) {
            log.warn("Corrupted geo location value for key [{}]: '{}' — treating as absent", key, value);
            return Optional.empty();
        }
    }

    @Override
    public void updateLocation(String userId, double latitude, double longitude,
                               long timestampMillis, long ttlHours) {
        String key   = KEY_PREFIX + userId;
        String value = latitude + DELIMITER + longitude + DELIMITER + timestampMillis;
        redisTemplate.opsForValue().set(key, value, Duration.ofHours(ttlHours));
        log.debug("Updated location for user [{}]: ({}, {}) at t={}", userId, latitude, longitude, timestampMillis);
    }
}
