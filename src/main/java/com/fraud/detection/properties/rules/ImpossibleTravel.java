package com.fraud.detection.properties.rules;

import lombok.Getter;
import lombok.Setter;

/** Impossible-travel check: implied speed between transactions is too high. */
@Getter
@Setter
public class ImpossibleTravel {

    /** Maximum plausible travel speed in km/h before the rule fires. */
    private double maxSpeedKmh;

    /**
     * Minimum elapsed time between transactions before speed is computed.
     * Below this threshold the implied speed is unreliable due to GPS noise
     * and near-simultaneous transactions at the same physical location.
     */
    private long minElapsedSeconds;

    /** Score contribution when this rule fires. */
    private int score;
}
