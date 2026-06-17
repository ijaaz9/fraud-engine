package com.fraud.detection.properties.rules;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class HighAmount {

    /** Amount above which a transaction is considered high-value. */
    private BigDecimal threshold;

    /** Score contribution when this rule fires. */
    private int score;
}
