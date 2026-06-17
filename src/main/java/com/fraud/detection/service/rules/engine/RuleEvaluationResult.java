package com.fraud.detection.service.rules.engine;

import com.fraud.detection.service.model.enums.FraudRuleType;
import lombok.Value;

@Value
public class RuleEvaluationResult {

    /** Which rule produced this result. */
    FraudRuleType ruleType;

    /** Whether this rule considers the transaction suspicious. */
    boolean triggered;

    /**
     * Score contribution when the rule fires (0 if not triggered).
     * These are summed by {@link com.frauddetection.scoring.SeverityScoreAggregator}
     * to derive the overall transaction risk score.
     */
    int score;

    /**
     * Human-readable description of why the rule fired, or an empty
     * string if it did not. Stored verbatim in the FraudFlag.detail field.
     *
     * Example: "15 transactions in the last 5 minutes (threshold: 10)"
     */
    String detail;

    // -------------------------------------------------------------------------
    // Static factory methods for clarity at call sites
    // -------------------------------------------------------------------------

    /**
     * Creates a result indicating the rule fired and should contribute to scoring.
     *
     * @param ruleType the rule that triggered
     * @param score    the score contribution
     * @param detail   explanation of why the rule fired
     */
    public static RuleEvaluationResult triggered(FraudRuleType ruleType, int score, String detail) {
        return new RuleEvaluationResult(ruleType, true, score, detail);
    }

    /**
     * Creates a result indicating the rule did not fire.
     * Score is always 0 for non-triggered results.
     *
     * @param ruleType the rule that was evaluated but did not trigger
     */
    public static RuleEvaluationResult notTriggered(FraudRuleType ruleType) {
        return new RuleEvaluationResult(ruleType, false, 0, "");
    }
}
