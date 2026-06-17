package com.fraud.detection.repository.redis.impl;

import com.fraud.detection.repository.redis.IdempotencyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * Redis implementation of {@link IdempotencyStore}.
 *
 * Key schema: "idempotency:{transactionId}"
 *
 * Uses SET NX (set-if-absent) with a TTL so each key is automatically
 * cleaned up after the guard window expires. This is a single atomic
 * round-trip — no separate EXISTS + SET needed.
 *
 * TTL should be long enough to cover the maximum realistic Kafka redelivery
 * window. 24 hours is a safe default for most deployments.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean isNewAndMark(String transactionId, long ttlHours) {
        String key = KEY_PREFIX + transactionId;
        Duration ttl = Duration.ofHours(ttlHours);

        // Returns true if key was set (i.e. this is a NEW, unseen transaction)
        // Returns false if key already existed (i.e. already processed — skip)
        Boolean wasAbsent = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);

        boolean isNew = Boolean.TRUE.equals(wasAbsent);
        if (!isNew) {
            log.warn("Duplicate Kafka message detected for transactionId [{}] — skipping processing", transactionId);
        }
        return isNew;
    }
}
