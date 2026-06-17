package com.fraud.detection.service.rules.impl;

import com.fraud.detection.service.model.enums.FraudRuleType;
import com.fraud.detection.service.model.event.TransactionEvent;
import com.fraud.detection.service.rules.engine.FraudRule;
import com.fraud.detection.service.rules.engine.RuleEvaluationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class HighAmountRule implements FraudRule {

    @Override
    public FraudRuleType getRuleType() {
        return FraudRuleType.HIGH_AMOUNT;
    }

    @Override
    public RuleEvaluationResult evaluate(TransactionEvent event) {
        log.debug("High amount check for transaction [{}]: amount={}, threshold={}",
                event.getTransactionId(), event.getAmount(), amountThreshold);

        if (event.getAmount().compareTo(amountThreshold) > 0) {
            String detail = String.format(
                    "Transaction amount %.2f exceeds high-value threshold of %.2f",
                    event.getAmount(), amountThreshold
            );
            return RuleEvaluationResult.triggered(getRuleType(), ruleScore, detail);
        }

        return RuleEvaluationResult.notTriggered(getRuleType());
    }
}
