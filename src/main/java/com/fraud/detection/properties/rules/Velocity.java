package com.fraud.detection.properties.rules;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Velocity {

    /** Maximum number of transactions allowed within the window. */
    private int threshold;

    /** Rolling window duration in seconds. */
    private long windowSeconds;

    /** Score contribution when this rule fires. */
    private int score;

}
