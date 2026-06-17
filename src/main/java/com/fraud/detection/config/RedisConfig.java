package com.fraud.detection.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration.
 *
 * We use {@link StringRedisTemplate} (rather than the generic RedisTemplate)
 * because all our Redis values are stored as plain strings or JSON strings.
 * StringRedisTemplate avoids the byte-level serialisation overhead of the
 * default JDK serializer and keeps Redis keys human-readable (useful for
 * debugging with redis-cli).
 *
 * Connection settings (host, port, password, pool size) are sourced from
 * application.yml via Spring Boot's RedisAutoConfiguration — no manual
 * LettuceConnectionFactory setup required.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        // Keys and values are UTF-8 strings — no binary serialisation
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
