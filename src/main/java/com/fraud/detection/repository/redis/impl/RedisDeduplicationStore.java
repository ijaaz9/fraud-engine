package com.fraud.detection.repository.redis.impl;

import com.fraud.detection.repository.redis.DeduplicationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisDeduplicationStore implements DeduplicationStore {

    private static final String KEY_PREFIX = "dup:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean isDuplicateAndRecord(String userId, String merchant, String amount, long windowSeconds) {
        String key = buildKey(userId, merchant, amount);
        Duration ttl = Duration.ofSeconds(windowSeconds);

        // SET NX returns true if the key was newly set (i.e. NOT a duplicate),
        // false if the key already existed (IS a duplicate).
        Boolean wasAbsent = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);

        boolean isDuplicate = Boolean.FALSE.equals(wasAbsent);
        log.debug("Dedup key [{}]: isDuplicate={}", key, isDuplicate);
        return isDuplicate;
    }

    /**
     * Builds a normalised Redis key from the transaction fingerprint.
     * Lowercasing and removing whitespace ensures "ACME Corp" and "acme corp"
     * produce identical keys, preventing missed duplicates due to casing.
     */
    private String buildKey(String userId, String merchant, String amount) {
        String normalisedMerchant = merchant.toLowerCase().replaceAll("\\s+", "_");
        return KEY_PREFIX + userId + ":" + normalisedMerchant + ":" + amount;
    }
}
