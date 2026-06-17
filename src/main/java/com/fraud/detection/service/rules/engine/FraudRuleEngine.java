package com.fraud.detection.service.rules.engine;

import com.fraud.detection.service.model.event.TransactionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FraudRuleEngine {

    /** All FraudRule implementations discovered by Spring's component scan. */
    private final List<FraudRule> rules;

    /**
     * Evaluates every registered fraud rule against the given transaction.
     *
     * @param event the inbound transaction to assess
     * @return list of results from every rule, in registration order;
     *         callers should check {@link RuleEvaluationResult#isTriggered()}
     *         on each entry to determine which rules fired
     */
    public List<RuleEvaluationResult> evaluate(TransactionEvent event) {
        log.debug("Evaluating {} fraud rules for transaction [{}]",
                rules.size(), event.getTransactionId());

        List<RuleEvaluationResult> results = rules.stream()
                .map(rule -> evaluateSafely(rule, event))
                .toList();

        long triggeredCount = results.stream().filter(RuleEvaluationResult::isTriggered).count();
        log.debug("Transaction [{}]: {}/{} rules triggered",
                event.getTransactionId(), triggeredCount, rules.size());

        return results;
    }

    /**
     * Wraps a single rule evaluation in a try-catch so that a bug or
     * unexpected exception in one rule does not abort evaluation of
     * the remaining rules for the transaction.
     *
     * In production, a failing rule logs an error and returns a
     * non-triggered result rather than propagating the exception.
     */
    private RuleEvaluationResult evaluateSafely(FraudRule rule, TransactionEvent event) {
        try {
            return rule.evaluate(event);
        } catch (Exception ex) {
            log.error("Rule [{}] threw an unexpected exception for transaction [{}] — treating as not triggered",
                    rule.getRuleType(), event.getTransactionId(), ex);
            return RuleEvaluationResult.notTriggered(rule.getRuleType());
        }
    }
}
