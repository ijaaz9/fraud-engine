package za.co.capitec.frauddetection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Fraud Detection Service.
 *
 * This service consumes transaction events from Kafka, applies a suite of
 * configurable fraud rules, scores each transaction, and persists results
 * to PostgreSQL (system of record) with Redis used for fast stateful
 * lookups (velocity windows, duplicate detection, idempotency guards).
 *
 * Fraud flags are then exposed via a REST API for querying and reporting.
 */
@SpringBootApplication
public class FraudDetectionEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudDetectionEngineApplication.class, args);
    }
}
