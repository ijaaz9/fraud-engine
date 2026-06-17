package com.fraud.detection.service.rules.engine;

import com.fraud.detection.service.model.event.TransactionEvent;
import com.fraud.detection.service.model.enums.FraudRuleType;

public interface FraudRule {

    RuleEvaluationResult evaluate(TransactionEvent event);

    FraudRuleType getRuleType();

}
