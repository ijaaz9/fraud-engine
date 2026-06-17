package com.fraud.detection.rules;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.properties.FraudRuleProperties;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import com.fraud.detection.rules.impl.HighAmountRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HighAmountRule")
class HighAmountRuleTest {

    private HighAmountRule rule;

    @BeforeEach
    void setUp() {
        // Real properties object — nested groups are default-initialised,
        // so no Spring context or reflection is needed for unit tests.
        FraudRuleProperties properties = new FraudRuleProperties();
        properties.getHighAmount().setThreshold(new BigDecimal("10000"));
        properties.getHighAmount().setScore(30);
        rule = new HighAmountRule(properties);
    }

    @Test
    @DisplayName("should NOT trigger when amount is below threshold")
    void shouldNotTriggerBelowThreshold() {
        TransactionEvent event = buildEvent("9999.99");

        RuleEvaluationResult result = rule.evaluate(event);

        assertThat(result.isTriggered()).isFalse();
        assertThat(result.getScore()).isZero();
        assertThat(result.getRuleType()).isEqualTo(FraudRuleType.HIGH_AMOUNT);
    }

    @Test
    @DisplayName("should NOT trigger when amount exactly equals threshold")
    void shouldNotTriggerAtExactThreshold() {
        TransactionEvent event = buildEvent("10000.00");

        RuleEvaluationResult result = rule.evaluate(event);

        assertThat(result.isTriggered()).isFalse();
    }

    @Test
    @DisplayName("should trigger when amount exceeds threshold")
    void shouldTriggerAboveThreshold() {
        TransactionEvent event = buildEvent("10000.01");

        RuleEvaluationResult result = rule.evaluate(event);

        assertThat(result.isTriggered()).isTrue();
        assertThat(result.getScore()).isEqualTo(30);
        assertThat(result.getDetail()).contains("10000.01");
        assertThat(result.getRuleType()).isEqualTo(FraudRuleType.HIGH_AMOUNT);
    }

    @Test
    @DisplayName("should trigger with correct score for very large amounts")
    void shouldTriggerForVeryLargeAmount() {
        TransactionEvent event = buildEvent("999999.00");

        RuleEvaluationResult result = rule.evaluate(event);

        assertThat(result.isTriggered()).isTrue();
        assertThat(result.getScore()).isEqualTo(30);
    }

    private TransactionEvent buildEvent(String amount) {
        return TransactionEvent.builder()
                .transactionId("txn-001")
                .userId("user-001")
                .amount(new BigDecimal(amount))
                .merchant("Test Merchant")
                .category("ELECTRONICS")
                .latitude(51.5074)
                .longitude(-0.1278)
                .timestamp(Instant.now())
                .build();
    }
}
