package com.fraud.detection.properties.rules;

import lombok.Getter;
import lombok.Setter;

/** Geo-anomaly check: large jump from the last known location. */
@Getter
@Setter
public class GeoAnomaly {

    /** Distance in km beyond which a location change is considered anomalous. */
    private double distanceThresholdKm;

    /** TTL for the stored last-known-location (hours). */
    private long locationTtlHours;

    /** Score contribution when this rule fires. */
    private int score;
}
