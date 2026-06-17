package com.fraud.detection.rules;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.properties.FraudRuleProperties;
import com.fraud.detection.repository.redis.GeoLocationStore;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import com.fraud.detection.rules.impl.GeoAnomalyRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GeoAnomalyRule")
@ExtendWith(MockitoExtension.class)
class GeoAnomalyRuleTest {

    @Mock
    private GeoLocationStore geoLocationStore;

    private GeoAnomalyRule rule;

    // London coordinates
    private static final double LONDON_LAT = 51.5074;
    private static final double LONDON_LON = -0.1278;

    // Paris coordinates (~340km from London)
    private static final double PARIS_LAT = 48.8566;
    private static final double PARIS_LON = 2.3522;

    // New York coordinates (~5,570km from London)
    private static final double NEW_YORK_LAT = 40.7128;
    private static final double NEW_YORK_LON = -74.0060;

    @BeforeEach
    void setUp() {
        FraudRuleProperties properties = new FraudRuleProperties();
        properties.getGeoAnomaly().setDistanceThresholdKm(500.0);
        properties.getGeoAnomaly().setLocationTtlHours(24L);
        properties.getGeoAnomaly().setScore(15);
        rule = new GeoAnomalyRule(geoLocationStore, properties);
    }

    @Test
    @DisplayName("should NOT trigger on first transaction (no prior location)")
    void shouldNotTriggerWithNoPriorLocation() {
        when(geoLocationStore.getLastLocation(anyString())).thenReturn(Optional.empty());

        RuleEvaluationResult result = rule.evaluate(buildEvent(LONDON_LAT, LONDON_LON));

        assertThat(result.isTriggered()).isFalse();
        assertThat(result.getRuleType()).isEqualTo(FraudRuleType.GEO_ANOMALY);
    }

    @Test
    @DisplayName("should NOT trigger when distance is within threshold (London → Paris, ~340km)")
    void shouldNotTriggerWithinThreshold() {
        // 3-element array: [lat, lon, timestampMillis]
        when(geoLocationStore.getLastLocation(anyString()))
                .thenReturn(Optional.of(new double[]{LONDON_LAT, LONDON_LON, Instant.now().toEpochMilli()}));

        RuleEvaluationResult result = rule.evaluate(buildEvent(PARIS_LAT, PARIS_LON));

        assertThat(result.isTriggered()).isFalse();
    }

    @Test
    @DisplayName("should trigger when distance exceeds threshold (London → New York, ~5570km)")
    void shouldTriggerBeyondThreshold() {
        when(geoLocationStore.getLastLocation(anyString()))
                .thenReturn(Optional.of(new double[]{LONDON_LAT, LONDON_LON, Instant.now().toEpochMilli()}));

        RuleEvaluationResult result = rule.evaluate(buildEvent(NEW_YORK_LAT, NEW_YORK_LON));

        assertThat(result.isTriggered()).isTrue();
        assertThat(result.getScore()).isEqualTo(15);
        assertThat(result.getRuleType()).isEqualTo(FraudRuleType.GEO_ANOMALY);
    }

    @Test
    @DisplayName("should always update the stored location with timestamp after evaluation")
    void shouldAlwaysUpdateLocationWithTimestamp() {
        when(geoLocationStore.getLastLocation(anyString())).thenReturn(Optional.empty());
        TransactionEvent event = buildEvent(LONDON_LAT, LONDON_LON);

        rule.evaluate(event);

        // Verify the new 4-arg signature is called with the event's timestamp
        verify(geoLocationStore).updateLocation(
                eq("user-001"),
                eq(LONDON_LAT),
                eq(LONDON_LON),
                eq(event.getTimestamp().toEpochMilli()),
                eq(24L)
        );
    }

    @Test
    @DisplayName("Haversine: London to Paris should be approximately 343km")
    void haversineLondonParis() {
        double distance = GeoAnomalyRule.haversineDistanceKm(
                LONDON_LAT, LONDON_LON, PARIS_LAT, PARIS_LON);
        assertThat(distance).isCloseTo(343.0, within(5.0));
    }

    @Test
    @DisplayName("Haversine: same point should return zero distance")
    void haversineSamePoint() {
        double distance = GeoAnomalyRule.haversineDistanceKm(
                LONDON_LAT, LONDON_LON, LONDON_LAT, LONDON_LON);
        assertThat(distance).isCloseTo(0.0, within(0.001));
    }

    @Test
    @DisplayName("Haversine: London to New York should be approximately 5570km")
    void haversineLondonNewYork() {
        double distance = GeoAnomalyRule.haversineDistanceKm(
                LONDON_LAT, LONDON_LON, NEW_YORK_LAT, NEW_YORK_LON);
        assertThat(distance).isCloseTo(5570.0, within(30.0));
    }

    private TransactionEvent buildEvent(double latitude, double longitude) {
        return TransactionEvent.builder()
                .transactionId("txn-001")
                .userId("user-001")
                .amount(new BigDecimal("100.00"))
                .merchant("Test Merchant")
                .category("TRAVEL")
                .latitude(latitude)
                .longitude(longitude)
                .timestamp(Instant.now())
                .build();
    }
}