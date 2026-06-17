package com.fraud.detection.rules.impl;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.properties.FraudRuleProperties;
import com.fraud.detection.rules.engine.FraudRule;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HighAmountRule implements FraudRule {

    private final FraudRuleProperties properties;

    @Override
    public FraudRuleType getRuleType() {
        return FraudRuleType.HIGH_AMOUNT;
    }

    @Override
    public RuleEvaluationResult evaluate(TransactionEvent event) {
        log.debug("High amount check for transaction [{}]: amount={}, threshold={}",
                event.getTransactionId(), event.getAmount(), properties.getHighAmount().getThreshold());

        if (event.getAmount().compareTo(properties.getHighAmount().getThreshold()) > 0) {
            String detail = String.format(
                    "Transaction amount %.2f exceeds high-value threshold of %.2f",
                    event.getAmount(), properties.getHighAmount().getThreshold()
            );
            return RuleEvaluationResult.triggered(getRuleType(), properties.getHighAmount().getScore(), detail);
        }

        return RuleEvaluationResult.notTriggered(getRuleType());
    }
}
