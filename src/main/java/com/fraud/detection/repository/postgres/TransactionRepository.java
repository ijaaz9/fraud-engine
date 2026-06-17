package com.fraud.detection.repository.postgres;

import com.fraud.detection.model.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Transaction} persistence.
 *
 * Spring generates the implementation at runtime — no boilerplate needed.
 * Custom query methods follow Spring Data's naming convention, which the
 * framework resolves to JPQL automatically.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Looks up a transaction by its external identifier (the ID sourced from
     * the Kafka event, used for idempotency checks at the DB level).
     */
    Optional<Transaction> findByTransactionId(String transactionId);

    /**
     * Checks existence by external transaction ID without loading the full entity.
     * Used as a secondary idempotency guard (Redis is primary).
     */
    boolean existsByTransactionId(String transactionId);
}
