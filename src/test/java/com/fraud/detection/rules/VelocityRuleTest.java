package com.fraud.detection.rules;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.repository.redis.VelocityStore;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import com.fraud.detection.rules.impl.VelocityRule;
import org.junit.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("VelocityRule")
@ExtendWith(MockitoExtension.class)
class VelocityRuleTest {

    @Mock
    private VelocityStore velocityStore;

    @InjectMocks
    private VelocityRule rule;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rule, "threshold", 10);
        ReflectionTestUtils.setField(rule, "windowSeconds", 300L);
        ReflectionTestUtils.setField(rule, "ruleScore", 40);
    }

    @Test
    @DisplayName("should NOT trigger when transaction count is at or below threshold")
    void shouldNotTriggerAtThreshold() {
        when(velocityStore.recordAndCount(anyString(), anyLong(), anyLong())).thenReturn(10L);

        RuleEvaluationResult result = rule.evaluate(buildEvent());

        assertThat(result.isTriggered()).isFalse();
        assertThat(result.getScore()).isZero();
    }

    @Test
    @DisplayName("should trigger when transaction count exceeds threshold")
    void shouldTriggerAboveThreshold() {
        when(velocityStore.recordAndCount(anyString(), anyLong(), anyLong())).thenReturn(11L);

        RuleEvaluationResult result = rule.evaluate(buildEvent());

        assertThat(result.isTriggered()).isTrue();
        assertThat(result.getScore()).isEqualTo(40);
        assertThat(result.getDetail()).contains("11");
        assertThat(result.getRuleType()).isEqualTo(FraudRuleType.VELOCITY_CHECK);
    }

    @Test
    @DisplayName("should pass correct userId and window to the velocity store")
    void shouldPassCorrectParametersToStore() {
        TransactionEvent event = buildEvent();
        when(velocityStore.recordAndCount(anyString(), anyLong(), anyLong())).thenReturn(1L);

        rule.evaluate(event);

        verify(velocityStore).recordAndCount(
                eq("user-001"),
                eq(event.getTimestamp().toEpochMilli()),
                eq(300L)
        );
    }

    private TransactionEvent buildEvent() {
        return TransactionEvent.builder()
                .transactionId("txn-001")
                .userId("user-001")
                .amount(new BigDecimal("100.00"))
                .merchant("Test Merchant")
                .category("RETAIL")
                .latitude(51.5074)
                .longitude(-0.1278)
                .timestamp(Instant.now())
                .build();
    }
}