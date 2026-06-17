package com.fraud.detection.api.controller;


import com.fraud.detection.api.dto.FraudFlagDto;
import com.fraud.detection.model.enums.FlagStatus;
import com.fraud.detection.service.FraudFlagQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * REST controller exposing fraud flag retrieval endpoints.
 *
 * Base path: /api/v1/flags
 *
 * All endpoints are read-only (GET). Write operations happen asynchronously
 * via the Kafka consumer pipeline — there are no synchronous ingest endpoints.
 *
 * === Endpoints ===
 *   GET /api/v1/flags                    — paginated list of flags
 *   GET /api/v1/flags/{transactionId}    — all flags for a specific transaction
 *   GET /api/v1/flags/stats              — aggregated statistics
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/flags")
@RequiredArgsConstructor
public class FraudFlagController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FraudFlagQueryService queryService;

    /**
     * Returns a paginated list of fraud flags, sorted by creation time descending.
     *
     * @param status optional filter: OPEN or REVIEWED (omit for all)
     * @param page   zero-based page number (default: 0)
     * @param size   results per page, max 100 (default: 20)
     *
     * Example: GET /api/v1/flags?status=OPEN&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<FraudFlagDto.PagedFlagResponse> getFlags(
            @RequestParam(required = false) FlagStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // Guard against oversized page requests
        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);

        log.debug("GET /api/v1/flags — status={}, page={}, size={}", status, page, effectiveSize);
        FraudFlagDto.PagedFlagResponse response = queryService.getFlags(status, page, effectiveSize);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns all fraud flags raised for a specific transaction.
     *
     * @param transactionId the external transaction ID (from the Kafka event)
     * @return 200 with flag details, or 404 if the transaction is not found
     *
     * Example: GET /api/v1/transactions/txn-abc-123/flags
     */
    @GetMapping("/transactions/{transactionId}/flags")
    public ResponseEntity<FraudFlagDto.TransactionFlagDetail> getFlagsForTransaction(
            @PathVariable String transactionId
    ) {
        log.debug("GET /api/v1/flag/transactions/{}/flags", transactionId);
        FraudFlagDto.TransactionFlagDetail detail = queryService.getFlagsForTransaction(transactionId);
        return ResponseEntity.ok(detail);
    }

    /**
     * Returns aggregated fraud statistics for a time range and optional category.
     *
     * Both {@code from} and {@code to} are required ISO-8601 UTC timestamps.
     *
     * @param from     start of the reporting period (inclusive), e.g. 2024-03-01T00:00:00Z
     * @param to       end of the reporting period (inclusive),   e.g. 2024-03-31T23:59:59Z
     * @param category optional merchant category filter (e.g. ELECTRONICS, TRAVEL)
     *
     * Example: GET /api/v1/flags/stats?from=2024-03-01T00:00:00Z&to=2024-03-31T23:59:59Z&category=TRAVEL
     */
    @GetMapping("/stats")
    public ResponseEntity<FraudFlagDto.FraudStatsResponse> getStats(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String category
    ) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        log.debug("GET /api/v1/flags/stats — from={}, to={}, category={}", from, to, category);
        FraudFlagDto.FraudStatsResponse stats = queryService.getStats(from, to, category);
        return ResponseEntity.ok(stats);
    }
}
