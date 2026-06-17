package com.fraud.detection.rules.impl;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.repository.redis.DeduplicationStore;
import com.fraud.detection.rules.engine.FraudRule;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicateTransactionRule implements FraudRule {

    private final DeduplicationStore deduplicationStore;

    @Override
    public FraudRuleType getRuleType() {
        return FraudRuleType.DUPLICATE_TRANSACTION;
    }

    @Override
    public RuleEvaluationResult evaluate(TransactionEvent event) {
        // Normalise amount to 2dp string to avoid floating point key mismatches
        String amountKey = event.getAmount().toPlainString();

        boolean isDuplicate = deduplicationStore.isDuplicateAndRecord(
                event.getUserId(),
                event.getMerchant(),
                amountKey,
                windowSeconds
        );

        log.debug("Duplicate check for user [{}] at merchant [{}] amount [{}]: duplicate={}",
                event.getUserId(), event.getMerchant(), amountKey, isDuplicate);

        if (isDuplicate) {
            String detail = String.format(
                    "Duplicate transaction detected: user [%s] at merchant [%s] for amount %s " +
                            "within the last %d seconds",
                    event.getUserId(), event.getMerchant(), amountKey, windowSeconds
            );
            return RuleEvaluationResult.triggered(getRuleType(), ruleScore, detail);
        }

        return RuleEvaluationResult.notTriggered(getRuleType());
    }
}
