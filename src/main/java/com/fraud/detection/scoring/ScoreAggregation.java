package com.fraud.detection.scoring;

import com.fraud.detection.model.enums.Severity;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import lombok.Value;

import java.util.List;

@Value
public class ScoreAggregation {

    /** Sum of scores from all triggered rules. */
    int totalScore;

    /** Severity level derived from the total score. */
    Severity severity;

    /**
     * The subset of rule results that fired (triggered == true).
     * Each entry in this list will produce one FraudFlag row.
     */
    List<RuleEvaluationResult> triggeredResults;

    /**
     * Returns true if at least one fraud rule fired (i.e. the transaction
     * should be flagged and fraud flags persisted).
     */
    public boolean isFraudulent() {
        return !triggeredResults.isEmpty();
    }
}
