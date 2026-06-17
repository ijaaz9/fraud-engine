package com.fraud.detection.repository.postgres;

import com.fraud.detection.model.entity.FraudFlag;
import com.fraud.detection.model.enums.FlagStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link FraudFlag} persistence and retrieval.
 *
 * Supports the three retrieval API endpoints:
 *   1. Paginated list of flagged transactions (with optional status filter)
 *   2. All flags for a specific transaction ID
 *   3. Summary statistics by category and time range
 */
@Repository
public interface FraudFlagRepository extends JpaRepository<FraudFlag, Long> {

    /**
     * Returns a paginated list of fraud flags, optionally filtered by status.
     * JOIN FETCH on transaction avoids N+1 queries when the caller needs
     * transaction details alongside the flags.
     *
     * @param status   optional filter (pass null to return all statuses)
     * @param pageable pagination and sort parameters
     */
    @Query("""
            SELECT f FROM FraudFlag f
            JOIN FETCH f.transaction t
            WHERE (:status IS NULL OR f.status = :status)
            ORDER BY f.createdAt DESC
            """)
    Page<FraudFlag> findAllWithTransaction(@Param("status") FlagStatus status, Pageable pageable);

    /**
     * Returns all fraud flags raised for a specific transaction, identified
     * by the external transaction ID (not the internal DB primary key).
     */
    @Query("""
            SELECT f FROM FraudFlag f
            JOIN FETCH f.transaction t
            WHERE t.transactionId = :transactionId
            ORDER BY f.createdAt ASC
            """)
    List<FraudFlag> findAllByTransactionId(@Param("transactionId") String transactionId);

    /**
     * Aggregated stats query for the summary endpoint.
     * Groups by rule type and severity to give a breakdown of fraud signal distribution.
     *
     * Returns Object[] rows: [ruleTriggered, severity, count]
     *
     * @param from start of the time range (inclusive)
     * @param to   end of the time range (inclusive)
     * @param category optional merchant category filter (null = all categories)
     */
    @Query("""
            SELECT f.ruleTriggered, f.severity, COUNT(f)
            FROM FraudFlag f
            JOIN f.transaction t
            WHERE f.createdAt BETWEEN :from AND :to
            AND (:category IS NULL OR t.category = :category)
            GROUP BY f.ruleTriggered, f.severity
            ORDER BY COUNT(f) DESC
            """)
    List<Object[]> findStatsByTimeRangeAndCategory(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("category") String category
    );

    /**
     * Total count of flags within a time range — used for the stats summary header.
     */
    @Query("""
            SELECT COUNT(f) FROM FraudFlag f
            WHERE f.createdAt BETWEEN :from AND :to
            AND (:category IS NULL OR f.transaction.category = :category)
            """)
    long countByTimeRangeAndCategory(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("category") String category
    );
}
