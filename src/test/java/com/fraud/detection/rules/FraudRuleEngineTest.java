package com.fraud.detection.rules;

import org.junit.jupiter.api.DisplayName;

@DisplayName("FraudRuleEngine")
class FraudRuleEngineTest {

    @Test
    @DisplayName("should evaluate all registered rules for every transaction")
    void shouldEvaluateAllRules() {
        FraudRule ruleA = mockRule(FraudRuleType.HIGH_AMOUNT, false);
        FraudRule ruleB = mockRule(FraudRuleType.VELOCITY_CHECK, false);
        FraudRuleEngine engine = new FraudRuleEngine(List.of(ruleA, ruleB));

        engine.evaluate(buildEvent());

        verify(ruleA).evaluate(any());
        verify(ruleB).evaluate(any());
    }

    @Test
    @DisplayName("should return results for every rule including non-triggered ones")
    void shouldReturnResultsForAllRules() {
        FraudRule ruleA = mockRule(FraudRuleType.HIGH_AMOUNT, true);
        FraudRule ruleB = mockRule(FraudRuleType.VELOCITY_CHECK, false);
        FraudRuleEngine engine = new FraudRuleEngine(List.of(ruleA, ruleB));

        List<RuleEvaluationResult> results = engine.evaluate(buildEvent());

        assertThat(results).hasSize(2);
        assertThat(results).anyMatch(r -> r.getRuleType() == FraudRuleType.HIGH_AMOUNT && r.isTriggered());
        assertThat(results).anyMatch(r -> r.getRuleType() == FraudRuleType.VELOCITY_CHECK && !r.isTriggered());
    }

    @Test
    @DisplayName("should not abort remaining rules when one rule throws an exception")
    void shouldContinueEvaluationWhenOneRuleThrows() {
        FraudRule brokenRule = mock(FraudRule.class);
        when(brokenRule.getRuleType()).thenReturn(FraudRuleType.HIGH_AMOUNT);
        when(brokenRule.evaluate(any())).thenThrow(new RuntimeException("Redis unavailable"));

        FraudRule healthyRule = mockRule(FraudRuleType.VELOCITY_CHECK, true);
        FraudRuleEngine engine = new FraudRuleEngine(List.of(brokenRule, healthyRule));

        List<RuleEvaluationResult> results = engine.evaluate(buildEvent());

        // Engine should return 2 results — one safe non-triggered (from the failed rule)
        // and one triggered (from the healthy rule)
        assertThat(results).hasSize(2);
        assertThat(results).anyMatch(r -> r.getRuleType() == FraudRuleType.HIGH_AMOUNT && !r.isTriggered());
        assertThat(results).anyMatch(r -> r.getRuleType() == FraudRuleType.VELOCITY_CHECK && r.isTriggered());
    }

    @Test
    @DisplayName("should return empty list when no rules are registered")
    void shouldReturnEmptyListWithNoRules() {
        FraudRuleEngine engine = new FraudRuleEngine(List.of());

        List<RuleEvaluationResult> results = engine.evaluate(buildEvent());

        assertThat(results).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FraudRule mockRule(FraudRuleType type, boolean triggered) {
        FraudRule rule = mock(FraudRule.class);
        when(rule.getRuleType()).thenReturn(type);
        RuleEvaluationResult result = triggered
                ? RuleEvaluationResult.triggered(type, 30, "triggered detail")
                : RuleEvaluationResult.notTriggered(type);
        when(rule.evaluate(any())).thenReturn(result);
        return rule;
    }

    private TransactionEvent buildEvent() {
        return TransactionEvent.builder()
                .transactionId("txn-001")
                .userId("user-001")
                .amount(new BigDecimal("500.00"))
                .merchant("Test Merchant")
                .category("RETAIL")
                .latitude(51.5074)
                .longitude(-0.1278)
                .timestamp(Instant.now())
                .build();
    }
}
