package com.fraud.detection.rules.engine;

import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.model.enums.FraudRuleType;

public interface FraudRule {

    RuleEvaluationResult evaluate(TransactionEvent event);

    FraudRuleType getRuleType();

}
