package com.fraud.detection.properties.rules;

import lombok.Getter;
import lombok.Setter;

/** Duplicate transaction check (same user/merchant/amount). */
@Getter
@Setter
public class Duplicate {

    /** Window within which a repeated txn (same user/merchant/amount) is flagged. */
    private long windowSeconds;

    /** Score contribution when this rule fires. */
    private int score;
}
