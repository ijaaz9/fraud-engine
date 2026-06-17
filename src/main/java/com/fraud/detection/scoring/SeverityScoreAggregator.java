package com.fraud.detection.scoring;

import com.fraud.detection.model.enums.Severity;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class SeverityScoreAggregator {

    /**
     * Computes the aggregate score and severity for a transaction from the
     * list of rule evaluation results.
     *
     * @param results results from all rules evaluated by the FraudRuleEngine
     * @return a {@link ScoreAggregation} with the total score, severity, and
     *         the subset of results that actually triggered
     */
    public ScoreAggregation aggregate(List<RuleEvaluationResult> results) {
        List<RuleEvaluationResult> triggered = results.stream()
                .filter(RuleEvaluationResult::isTriggered)
                .toList();

        int totalScore = triggered.stream()
                .mapToInt(RuleEvaluationResult::getScore)
                .sum();

        Severity severity = Severity.fromScore(totalScore);

        log.debug("Score aggregation: {} rule(s) triggered, total score={}, severity={}",
                triggered.size(), totalScore, severity);

        return new ScoreAggregation(totalScore, severity, triggered);
    }
}
