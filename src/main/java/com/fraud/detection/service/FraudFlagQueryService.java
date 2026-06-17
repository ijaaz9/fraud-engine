package com.fraud.detection.service;


import com.fraud.detection.api.dto.FraudFlagDto;
import com.fraud.detection.model.entity.FraudFlag;
import com.fraud.detection.model.entity.Transaction;
import com.fraud.detection.model.enums.FlagStatus;
import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.enums.Severity;
import com.fraud.detection.repository.postgres.FraudFlagRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only service handling all fraud flag query operations for the REST API.
 *
 * Separating read operations into their own service (CQRS-lite) means:
 *   - The write path ({@link FraudDetectionService}) remains focused on processing.
 *   - Read queries can be optimised independently (e.g. moved to a read replica).
 *   - All DTO mapping lives here, not scattered across controllers or repositories.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FraudFlagQueryService {

    private final FraudFlagRepository fraudFlagRepository;

    /**
     * Returns a paginated list of fraud flags, optionally filtered by status.
     *
     * @param status   optional status filter (OPEN / REVIEWED); null = all
     * @param page     zero-based page number
     * @param size     number of results per page (max 100 enforced in controller)
     */
    public FraudFlagDto.PagedFlagResponse getFlags(FlagStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<FraudFlag> flagPage = fraudFlagRepository.findAllWithTransaction(status, pageable);

        List<FraudFlagDto.FlagSummary> summaries = flagPage.getContent().stream()
                .map(this::toFlagSummary)
                .toList();

        return FraudFlagDto.PagedFlagResponse.builder()
                .content(summaries)
                .page(flagPage.getNumber())
                .size(flagPage.getSize())
                .totalElements(flagPage.getTotalElements())
                .totalPages(flagPage.getTotalPages())
                .last(flagPage.isLast())
                .build();
    }

    /**
     * Returns all fraud flags for a specific transaction, along with full
     * transaction details and an overall severity summary.
     *
     * @param transactionId the external transaction ID (from the Kafka event)
     * @throws com.fraud.detection.exception.TransactionNotFoundException if not found
     */
    public FraudFlagDto.TransactionFlagDetail getFlagsForTransaction(String transactionId) {
        List<FraudFlag> flags = fraudFlagRepository.findAllByTransactionId(transactionId);

        if (flags.isEmpty()) {
            throw new com.fraud.detection.exception.TransactionNotFoundException(transactionId);
        }

        Transaction txn = flags.get(0).getTransaction();

        // Overall severity = highest severity across all flags (they share the same
        // aggregate score so this will always be the same value on all flags, but
        // we compute it defensively in case of data inconsistencies)
        Severity overallSeverity = flags.stream()
                .map(FraudFlag::getSeverity)
                .max(Enum::compareTo)
                .orElse(Severity.NONE);

        int totalScore = flags.stream()
                .mapToInt(FraudFlag::getScore)
                .max()
                .orElse(0);

        List<FraudFlagDto.FlagEntry> flagEntries = flags.stream()
                .map(this::toFlagEntry)
                .toList();

        return FraudFlagDto.TransactionFlagDetail.builder()
                .transactionId(txn.getTransactionId())
                .userId(txn.getUserId())
                .amount(txn.getAmount())
                .merchant(txn.getMerchant())
                .category(txn.getCategory())
                .latitude(txn.getLatitude())
                .longitude(txn.getLongitude())
                .timestamp(txn.getTimestamp())
                .processedAt(txn.getProcessedAt())
                .overallSeverity(overallSeverity)
                .totalScore(totalScore)
                .flags(flagEntries)
                .build();
    }

    /**
     * Returns aggregated fraud statistics for a given time range and optional category.
     *
     * @param from     start of the time range (inclusive)
     * @param to       end of the time range (inclusive)
     * @param category optional merchant category filter; null = all categories
     */
    public FraudFlagDto.FraudStatsResponse getStats(Instant from, Instant to, String category) {
        long totalFlags = fraudFlagRepository.countByTimeRangeAndCategory(from, to, category);
        List<Object[]> rows = fraudFlagRepository.findStatsByTimeRangeAndCategory(from, to, category);

        // Group the raw [ruleType, severity, count] rows into two maps
        Map<String, Long> byRule = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();

        for (Object[] row : rows) {
            FraudRuleType rule = (FraudRuleType) row[0];
            Severity severity = (Severity) row[1];
            long count = (Long) row[2];

            byRule.merge(rule.name(), count, Long::sum);
            bySeverity.merge(severity.name(), count, Long::sum);
        }

        return FraudFlagDto.FraudStatsResponse.builder()
                .from(from)
                .to(to)
                .category(category)
                .totalFlags(totalFlags)
                .byRule(byRule)
                .bySeverity(bySeverity)
                .build();
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private FraudFlagDto.FlagSummary toFlagSummary(FraudFlag flag) {
        Transaction txn = flag.getTransaction();
        return FraudFlagDto.FlagSummary.builder()
                .flagId(flag.getId())
                .transactionId(txn.getTransactionId())
                .userId(txn.getUserId())
                .amount(txn.getAmount())
                .merchant(txn.getMerchant())
                .category(txn.getCategory())
                .ruleTriggered(flag.getRuleTriggered())
                .severity(flag.getSeverity())
                .score(flag.getScore())
                .detail(flag.getDetail())
                .status(flag.getStatus())
                .createdAt(flag.getCreatedAt())
                .build();
    }

    private FraudFlagDto.FlagEntry toFlagEntry(FraudFlag flag) {
        return FraudFlagDto.FlagEntry.builder()
                .flagId(flag.getId())
                .ruleTriggered(flag.getRuleTriggered())
                .severity(flag.getSeverity())
                .score(flag.getScore())
                .detail(flag.getDetail())
                .status(flag.getStatus())
                .createdAt(flag.getCreatedAt())
                .build();
    }
}
