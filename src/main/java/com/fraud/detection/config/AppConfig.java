package com.fraud.detection.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Application-wide Jackson and Bean Validation configuration.
 *
 * Jackson:
 *   - JavaTimeModule enables ser/deserialization of Java 8 date/time types
 *     (Instant, LocalDateTime, etc.) as ISO-8601 strings.
 *   - WRITE_DATES_AS_TIMESTAMPS disabled so dates appear as strings in JSON,
 *     not epoch numbers (improves API readability).
 *   - FAIL_ON_UNKNOWN_PROPERTIES disabled for forward-compatibility: new fields
 *     added to the Kafka event schema won't break existing consumers.
 *
 * Validator:
 *   - Explicitly defined so it can be injected into the Kafka consumer,
 *     which runs outside the normal Spring MVC validation lifecycle.
 */
@Configuration
public class AppConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    public Validator validator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        return factory.getValidator();
    }
}
