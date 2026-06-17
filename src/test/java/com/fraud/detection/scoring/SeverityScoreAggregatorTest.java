package com.fraud.detection.scoring;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.enums.Severity;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SeverityScoreAggregator")
class SeverityScoreAggregatorTest {

    private SeverityScoreAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new SeverityScoreAggregator();
    }

    @Test
    @DisplayName("should return NONE severity and zero score when no rules triggered")
    void shouldReturnNoneWhenNoRulesTriggered() {
        List<RuleEvaluationResult> results = List.of(
                RuleEvaluationResult.notTriggered(FraudRuleType.HIGH_AMOUNT),
                RuleEvaluationResult.notTriggered(FraudRuleType.VELOCITY_CHECK)
        );

        ScoreAggregation aggregation = aggregator.aggregate(results);

        assertThat(aggregation.getTotalScore()).isZero();
        assertThat(aggregation.getSeverity()).isEqualTo(Severity.NONE);
        assertThat(aggregation.isFraudulent()).isFalse();
        assertThat(aggregation.getTriggeredResults()).isEmpty();
    }

    @Test
    @DisplayName("should only sum scores from triggered rules")
    void shouldOnlySumTriggeredScores() {
        List<RuleEvaluationResult> results = List.of(
                RuleEvaluationResult.triggered(FraudRuleType.HIGH_AMOUNT, 30, "detail"),
                RuleEvaluationResult.notTriggered(FraudRuleType.VELOCITY_CHECK),  // score = 0
                RuleEvaluationResult.triggered(FraudRuleType.DUPLICATE_TRANSACTION, 25, "detail")
        );

        ScoreAggregation aggregation = aggregator.aggregate(results);

        assertThat(aggregation.getTotalScore()).isEqualTo(55);  // 30 + 25
        assertThat(aggregation.getTriggeredResults()).hasSize(2);
        assertThat(aggregation.isFraudulent()).isTrue();
    }

    @ParameterizedTest(name = "score={0} → severity={1}")
    @CsvSource({
            "0,  NONE",
            "1,  LOW",
            "30, LOW",
            "31, MEDIUM",
            "55, MEDIUM",
            "56, HIGH",
            "80, HIGH",
            "81, CRITICAL",
            "130, CRITICAL"
    })
    @DisplayName("should map aggregate score to correct severity band")
    void shouldMapScoreToCorrectSeverity(int totalScore, Severity expectedSeverity) {
        // Build a single triggered result with the required total score
        List<RuleEvaluationResult> results = totalScore > 0
                ? List.of(RuleEvaluationResult.triggered(FraudRuleType.HIGH_AMOUNT, totalScore, "detail"))
                : List.of(RuleEvaluationResult.notTriggered(FraudRuleType.HIGH_AMOUNT));

        ScoreAggregation aggregation = aggregator.aggregate(results);

        assertThat(aggregation.getSeverity()).isEqualTo(expectedSeverity);
    }

    @Test
    @DisplayName("should reach CRITICAL when high-weight rules fire together")
    void shouldReachCriticalWhenHighWeightRulesFire() {
        // velocity(40) + impossible-travel(60) = 100 → CRITICAL
        List<RuleEvaluationResult> results = List.of(
                RuleEvaluationResult.triggered(FraudRuleType.VELOCITY_CHECK, 40, "detail"),
                RuleEvaluationResult.triggered(FraudRuleType.IMPOSSIBLE_TRAVEL, 60, "detail")
        );

        ScoreAggregation aggregation = aggregator.aggregate(results);

        assertThat(aggregation.getTotalScore()).isEqualTo(100);
        assertThat(aggregation.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(aggregation.getTriggeredResults()).hasSize(2);
    }

    @Test
    @DisplayName("impossible travel alone should produce HIGH severity")
    void shouldProduceHighSeverityForImpossibleTravelAlone() {
        // impossible-travel(60) → HIGH (56–80 band)
        List<RuleEvaluationResult> results = List.of(
                RuleEvaluationResult.triggered(FraudRuleType.IMPOSSIBLE_TRAVEL, 60, "detail")
        );

        ScoreAggregation aggregation = aggregator.aggregate(results);

        assertThat(aggregation.getTotalScore()).isEqualTo(60);
        assertThat(aggregation.getSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    @DisplayName("geo anomaly alone should produce LOW severity")
    void shouldProduceLowSeverityForGeoAnomalyAlone() {
        // geo-anomaly(15) → LOW (1–30 band)
        List<RuleEvaluationResult> results = List.of(
                RuleEvaluationResult.triggered(FraudRuleType.GEO_ANOMALY, 15, "detail")
        );

        ScoreAggregation aggregation = aggregator.aggregate(results);

        assertThat(aggregation.getTotalScore()).isEqualTo(15);
        assertThat(aggregation.getSeverity()).isEqualTo(Severity.LOW);
    }

    @Test
    @DisplayName("geo anomaly + impossible travel together should produce HIGH severity")
    void shouldProduceHighSeverityForBothGeoRules() {
        // geo-anomaly(15) + impossible-travel(60) = 75 → HIGH (56–80 band)
        List<RuleEvaluationResult> results = List.of(
                RuleEvaluationResult.triggered(FraudRuleType.GEO_ANOMALY, 15, "detail"),
                RuleEvaluationResult.triggered(FraudRuleType.IMPOSSIBLE_TRAVEL, 60, "detail")
        );

        ScoreAggregation aggregation = aggregator.aggregate(results);

        assertThat(aggregation.getTotalScore()).isEqualTo(75);
        assertThat(aggregation.getSeverity()).isEqualTo(Severity.HIGH);
    }
}