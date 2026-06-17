package com.fraud.detection.repository.redis.impl;

import com.fraud.detection.repository.redis.VelocityStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisVelocityStore implements VelocityStore {


    private static final String KEY_PREFIX = "velocity:";

    // Key TTL is set to window + buffer so Redis auto-cleans idle user keys.
    private static final long TTL_BUFFER_SECONDS = 60L;

    private final StringRedisTemplate redisTemplate;

    @Override
    public long recordAndCount(String userId, long timestampMillis, long windowSeconds) {
        String key = KEY_PREFIX + userId;
        long windowStartMillis = timestampMillis - (windowSeconds * 1000);

        // Use timestamp + random suffix to allow multiple entries at the same millisecond.
        // Without uniqueness, ZADD would overwrite an existing member with the same value.
        String member = timestampMillis + ":" + Thread.currentThread().threadId();

        // 1. Add this transaction's timestamp to the sorted set
        redisTemplate.opsForZSet().add(key, member, timestampMillis);

        // 2. Remove entries that fall outside the rolling window
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStartMillis - 1);

        // 3. Count remaining entries (all within the window)
        Long count = redisTemplate.opsForZSet().count(key, windowStartMillis, timestampMillis);

        // Reset the key TTL so it doesn't expire while the user is active
        redisTemplate.expire(key, windowSeconds + TTL_BUFFER_SECONDS, TimeUnit.SECONDS);

        long result = count != null ? count : 1L;
        log.debug("Velocity key [{}]: {} entries in window", key, result);
        return result;
    }
}
