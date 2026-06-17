package com.fraud.detection.service;

import com.fraud.detection.model.entity.Transaction;
import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.enums.Severity;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.repository.postgres.FraudFlagRepository;
import com.fraud.detection.repository.postgres.TransactionRepository;
import com.fraud.detection.repository.redis.IdempotencyStore;
import com.fraud.detection.rules.engine.FraudRuleEngine;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import com.fraud.detection.scoring.ScoreAggregation;
import com.fraud.detection.scoring.SeverityScoreAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("FraudDetectionService")
@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock private FraudRuleEngine ruleEngine;
    @Mock private SeverityScoreAggregator scoreAggregator;
    @Mock private IdempotencyStore idempotencyStore;
    @Mock private TransactionRepository transactionRepository;
    @Mock private FraudFlagRepository fraudFlagRepository;

    @InjectMocks
    private FraudDetectionService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "idempotencyTtlHours", 24L);
    }

    @Test
    @DisplayName("should skip processing when transaction has already been processed (duplicate Kafka delivery)")
    void shouldSkipDuplicateTransaction() {
        when(idempotencyStore.isNewAndMark(anyString(), anyLong())).thenReturn(false);

        service.process(buildEvent("txn-001"));

        verifyNoInteractions(ruleEngine, scoreAggregator, transactionRepository, fraudFlagRepository);
    }

    @Test
    @DisplayName("should persist transaction even when no fraud rules fire")
    void shouldPersistTransactionWhenClean() {
        when(idempotencyStore.isNewAndMark(anyString(), anyLong())).thenReturn(true);
        when(ruleEngine.evaluate(any())).thenReturn(List.of());
        when(scoreAggregator.aggregate(any())).thenReturn(
                new ScoreAggregation(0, Severity.NONE, List.of())
        );
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.process(buildEvent("txn-001"));

        verify(transactionRepository).save(any(Transaction.class));
        verifyNoInteractions(fraudFlagRepository);
    }

    @Test
    @DisplayName("should persist fraud flags for every triggered rule")
    void shouldPersistFraudFlagsForTriggeredRules() {
        TransactionEvent event = buildEvent("txn-002");

        List<RuleEvaluationResult> triggeredResults = List.of(
                RuleEvaluationResult.triggered(FraudRuleType.HIGH_AMOUNT, 30, "Amount exceeded"),
                RuleEvaluationResult.triggered(FraudRuleType.VELOCITY_CHECK, 40, "Velocity exceeded")
        );

        when(idempotencyStore.isNewAndMark(anyString(), anyLong())).thenReturn(true);
        when(ruleEngine.evaluate(any())).thenReturn(triggeredResults);
        when(scoreAggregator.aggregate(any())).thenReturn(
                new ScoreAggregation(70, Severity.HIGH, triggeredResults)
        );
        Transaction savedTxn = buildTransactionEntity(event);
        when(transactionRepository.save(any())).thenReturn(savedTxn);

        service.process(event);

        // Verify flags were saved — capture the list passed to saveAll
        ArgumentCaptor<List> flagCaptor = ArgumentCaptor.forClass(List.class);
        verify(fraudFlagRepository).saveAll(flagCaptor.capture());
        assertThat(flagCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("should map TransactionEvent fields correctly to the persisted Transaction entity")
    void shouldMapEventToEntityCorrectly() {
        TransactionEvent event = buildEvent("txn-003");

        when(idempotencyStore.isNewAndMark(anyString(), anyLong())).thenReturn(true);
        when(ruleEngine.evaluate(any())).thenReturn(List.of());
        when(scoreAggregator.aggregate(any())).thenReturn(
                new ScoreAggregation(0, Severity.NONE, List.of())
        );

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        when(transactionRepository.save(txnCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        service.process(event);

        Transaction saved = txnCaptor.getValue();
        assertThat(saved.getTransactionId()).isEqualTo("txn-003");
        assertThat(saved.getUserId()).isEqualTo("user-001");
        assertThat(saved.getAmount()).isEqualByComparingTo("150.00");
        assertThat(saved.getMerchant()).isEqualTo("Test Merchant");
        assertThat(saved.getCategory()).isEqualTo("RETAIL");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TransactionEvent buildEvent(String transactionId) {
        return TransactionEvent.builder()
                .transactionId(transactionId)
                .userId("user-001")
                .amount(new BigDecimal("150.00"))
                .merchant("Test Merchant")
                .category("RETAIL")
                .latitude(51.5074)
                .longitude(-0.1278)
                .timestamp(Instant.now())
                .build();
    }

    private Transaction buildTransactionEntity(TransactionEvent event) {
        return Transaction.builder()
                .id(1L)
                .transactionId(event.getTransactionId())
                .userId(event.getUserId())
                .amount(event.getAmount())
                .merchant(event.getMerchant())
                .category(event.getCategory())
                .latitude(event.getLatitude())
                .longitude(event.getLongitude())
                .timestamp(event.getTimestamp())
                .build();
    }
}