package com.fraud.detection.rules.impl;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.properties.FraudRuleProperties;
import com.fraud.detection.repository.redis.GeoLocationStore;
import com.fraud.detection.rules.engine.FraudRule;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@Order(2) // Must run after ImpossibleTravelRule — reads the previous snapshot for the distance check, then updates it for the next transaction
@RequiredArgsConstructor
public class GeoAnomalyRule implements FraudRule {

    private final GeoLocationStore geoLocationStore;
    private final FraudRuleProperties properties;

    // Earth radius in km, used for Haversine calculation
    private static final double EARTH_RADIUS_KM = 6371.0;

    @Override
    public FraudRuleType getRuleType() {
        return FraudRuleType.GEO_ANOMALY;
    }

    @Override
    public RuleEvaluationResult evaluate(TransactionEvent event) {
        Optional<double[]> lastLocation = geoLocationStore.getLastLocation(event.getUserId());

        // Always update the stored location — win or lose — so the next
        // transaction evaluates against the most recent known position.
        // Timestamp is stored alongside coordinates for ImpossibleTravelRule.
        geoLocationStore.updateLocation(
                event.getUserId(),
                event.getLatitude(),
                event.getLongitude(),
                event.getTimestamp().toEpochMilli(),
                properties.getGeoAnomaly().getLocationTtlHours()
        );

        if (lastLocation.isEmpty()) {
            // No prior location on record — cannot flag anomaly on first txn.
            log.debug("No prior location for user [{}] — skipping geo anomaly check", event.getUserId());
            return RuleEvaluationResult.notTriggered(getRuleType());
        }

        // Index 0=lat, 1=lon, 2=timestampMillis (timestamp used by ImpossibleTravelRule, not here)
        double[] prev = lastLocation.get();
        double distanceKm = haversineDistanceKm(
                prev[0], prev[1],
                event.getLatitude(), event.getLongitude()
        );

        log.debug("Geo anomaly check for user [{}]: distance from last location = {}km (threshold: {}km)",
                event.getUserId(), String.format("%.1f", distanceKm), properties.getGeoAnomaly().getDistanceThresholdKm());

        if (distanceKm > properties.getGeoAnomaly().getDistanceThresholdKm()) {
            String detail = String.format(
                    "Transaction location (%.4f, %.4f) is %.1f km from user's last known location " +
                            "(%.4f, %.4f) — threshold is %.0f km",
                    event.getLatitude(), event.getLongitude(), distanceKm,
                    prev[0], prev[1], properties.getGeoAnomaly().getDistanceThresholdKm()
            );
            return RuleEvaluationResult.triggered(getRuleType(), properties.getGeoAnomaly().getScore(), detail);
        }

        return RuleEvaluationResult.notTriggered(getRuleType());
    }

    /**
     * Computes the great-circle distance between two lat/lon points using the
     * Haversine formula. Accurate to within ~0.3% for distances up to 20,000 km.
     *
     * @param lat1 latitude of point 1 (decimal degrees)
     * @param lon1 longitude of point 1 (decimal degrees)
     * @param lat2 latitude of point 2 (decimal degrees)
     * @param lon2 longitude of point 2 (decimal degrees)
     * @return distance in kilometres
     */
    public static double haversineDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
