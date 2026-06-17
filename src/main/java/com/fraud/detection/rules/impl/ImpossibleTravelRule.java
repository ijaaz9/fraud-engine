package com.fraud.detection.rules.impl;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.properties.FraudRuleProperties;
import com.fraud.detection.repository.redis.GeoLocationStore;
import com.fraud.detection.rules.engine.FraudRule;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImpossibleTravelRule implements FraudRule {

    private final GeoLocationStore geoLocationStore;
    private final FraudRuleProperties properties;

    @Override
    public FraudRuleType getRuleType() {
        return FraudRuleType.IMPOSSIBLE_TRAVEL;
    }

    @Override
    public RuleEvaluationResult evaluate(TransactionEvent event) {
        Optional<double[]> lastLocationOpt = geoLocationStore.getLastLocation(event.getUserId());

        if (lastLocationOpt.isEmpty()) {
            // No prior transaction on record — cannot evaluate travel speed.
            log.debug("No prior location for user [{}] — skipping impossible travel check",
                    event.getUserId());
            return RuleEvaluationResult.notTriggered(getRuleType());
        }

        double[] prev = lastLocationOpt.get();
        double prevLat            = prev[0];
        double prevLon            = prev[1];
        long   prevTimestampMillis = (long) prev[2];

        long currentTimestampMillis = event.getTimestamp().toEpochMilli();
        long elapsedMillis          = currentTimestampMillis - prevTimestampMillis;

        // Guard: if the current transaction is older than the stored one (out-of-order
        // Kafka delivery), elapsed time is negative. Skip rather than flag incorrectly.
        if (elapsedMillis <= 0) {
            log.debug("Out-of-order transaction for user [{}] — skipping impossible travel check",
                    event.getUserId());
            return RuleEvaluationResult.notTriggered(getRuleType());
        }

        long elapsedSeconds = elapsedMillis / 1000;

        // Guard: transactions too close in time produce unreliable speed estimates.
        if (elapsedSeconds < properties.getImpossibleTravel().getMinElapsedSeconds()) {
            log.debug("Elapsed time {}s is below minimum {}s for user [{}] — skipping speed check",
                    elapsedSeconds, properties.getImpossibleTravel().getMinElapsedSeconds(), event.getUserId());
            return RuleEvaluationResult.notTriggered(getRuleType());
        }

        double distanceKm   = GeoAnomalyRule.haversineDistanceKm(
                prevLat, prevLon,
                event.getLatitude(), event.getLongitude()
        );
        double elapsedHours = elapsedSeconds / 3600.0;
        double impliedSpeedKmh = distanceKm / elapsedHours;

        log.debug("Impossible travel check for user [{}]: {}km in {}s = {}km/h (max: {}km/h)",
                event.getUserId(),
                String.format("%.1f", distanceKm),
                elapsedSeconds,
                String.format("%.1f", impliedSpeedKmh),
                properties.getImpossibleTravel().getMaxSpeedKmh());

        if (impliedSpeedKmh > properties.getImpossibleTravel().getMaxSpeedKmh()) {
            String detail = String.format(
                    "Implied travel speed of %.0f km/h between (%.4f, %.4f) and (%.4f, %.4f) " +
                            "over %d seconds (%.0f km) exceeds maximum plausible speed of %.0f km/h",
                    impliedSpeedKmh,
                    prevLat, prevLon,
                    event.getLatitude(), event.getLongitude(),
                    elapsedSeconds,
                    distanceKm,
                    properties.getImpossibleTravel().getMaxSpeedKmh()
            );
            return RuleEvaluationResult.triggered(getRuleType(), properties.getImpossibleTravel().getScore(), detail);
        }

        return RuleEvaluationResult.notTriggered(getRuleType());
    }
}
