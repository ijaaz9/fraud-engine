package com.fraud.detection.rules;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.properties.FraudRuleProperties;
import com.fraud.detection.repository.redis.GeoLocationStore;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import com.fraud.detection.rules.impl.ImpossibleTravelRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("ImpossibleTravelRule")
@ExtendWith(MockitoExtension.class)
class ImpossibleTravelRuleTest {

    @Mock
    private GeoLocationStore geoLocationStore;

    private ImpossibleTravelRule rule;

    // Cape Town
    private static final double CT_LAT  = -33.9249;
    private static final double CT_LON  =  18.4241;

    // Johannesburg (~1,269 km from Cape Town)
    private static final double JHB_LAT = -26.2041;
    private static final double JHB_LON =  28.0473;

    // London (~9,670 km from Cape Town)
    private static final double LONDON_LAT =  51.5074;
    private static final double LONDON_LON =  -0.1278;

    private static final Instant BASE_TIME = Instant.parse("2026-01-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        FraudRuleProperties properties = new FraudRuleProperties();
        properties.getImpossibleTravel().setMaxSpeedKmh(1200.0);
        properties.getImpossibleTravel().setMinElapsedSeconds(60L);
        properties.getImpossibleTravel().setScore(60);
        rule = new ImpossibleTravelRule(geoLocationStore, properties);
    }

    @Test
    @DisplayName("should NOT trigger when no prior location is on record")
    void shouldNotTriggerWithNoPriorLocation() {
        when(geoLocationStore.getLastLocation(anyString())).thenReturn(Optional.empty());

        RuleEvaluationResult result = rule.evaluate(buildEvent(CT_LAT, CT_LON, BASE_TIME));

        assertThat(result.isTriggered()).isFalse();
        assertThat(result.getRuleType()).isEqualTo(FraudRuleType.IMPOSSIBLE_TRAVEL);
    }

    @Test
    @DisplayName("should NOT trigger when elapsed time is below minimum (guards against GPS noise)")
    void shouldNotTriggerWhenElapsedTimeBelowMinimum() {
        // 30 seconds ago — below minElapsedSeconds threshold of 60
        long prevTimestamp = BASE_TIME.minusSeconds(30).toEpochMilli();
        when(geoLocationStore.getLastLocation(anyString()))
                .thenReturn(Optional.of(new double[]{CT_LAT, CT_LON, prevTimestamp}));

        RuleEvaluationResult result = rule.evaluate(buildEvent(LONDON_LAT, LONDON_LON, BASE_TIME));

        assertThat(result.isTriggered()).isFalse();
    }

    @Test
    @DisplayName("should NOT trigger on out-of-order event (current timestamp older than stored)")
    void shouldNotTriggerOnOutOfOrderDelivery() {
        // Stored snapshot has a LATER timestamp than the current event — Kafka reordering
        long futureTimestamp = BASE_TIME.plusSeconds(3600).toEpochMilli();
        when(geoLocationStore.getLastLocation(anyString()))
                .thenReturn(Optional.of(new double[]{CT_LAT, CT_LON, futureTimestamp}));

        RuleEvaluationResult result = rule.evaluate(buildEvent(LONDON_LAT, LONDON_LON, BASE_TIME));

        assertThat(result.isTriggered()).isFalse();
    }

    @Test
    @DisplayName("should NOT trigger for realistic air travel (Cape Town → Johannesburg, 3hrs, ~423 km/h)")
    void shouldNotTriggerForRealisticAirTravel() {
        // ~1,269 km in 3 hours → ~423 km/h, well within the 1,200 km/h threshold
        when(geoLocationStore.getLastLocation(anyString()))
                .thenReturn(Optional.of(new double[]{CT_LAT, CT_LON, BASE_TIME.toEpochMilli()}));

        RuleEvaluationResult result = rule.evaluate(
                buildEvent(JHB_LAT, JHB_LON, BASE_TIME.plusSeconds(10800)));

        assertThat(result.isTriggered()).isFalse();
    }

    @Test
    @DisplayName("should trigger for impossible travel (Cape Town → London, 1hr, ~9,670 km/h)")
    void shouldTriggerForImpossibleTravel() {
        // ~9,670 km in 1 hour → ~9,670 km/h >> 1,200 km/h threshold
        when(geoLocationStore.getLastLocation(anyString()))
                .thenReturn(Optional.of(new double[]{CT_LAT, CT_LON, BASE_TIME.toEpochMilli()}));

        RuleEvaluationResult result = rule.evaluate(
                buildEvent(LONDON_LAT, LONDON_LON, BASE_TIME.plusSeconds(3600)));

        assertThat(result.isTriggered()).isTrue();
        assertThat(result.getScore()).isEqualTo(60);
        assertThat(result.getRuleType()).isEqualTo(FraudRuleType.IMPOSSIBLE_TRAVEL);
        assertThat(result.getDetail()).contains("km/h");
    }

    @Test
    @DisplayName("should NOT trigger for long-haul flight (Cape Town → London, 13hrs, ~744 km/h)")
    void shouldNotTriggerForLongHaulFlight() {
        // ~9,670 km in 13 hours → ~744 km/h, below the 1,200 km/h threshold
        when(geoLocationStore.getLastLocation(anyString()))
                .thenReturn(Optional.of(new double[]{CT_LAT, CT_LON, BASE_TIME.toEpochMilli()}));

        RuleEvaluationResult result = rule.evaluate(
                buildEvent(LONDON_LAT, LONDON_LON, BASE_TIME.plusSeconds(46800)));

        assertThat(result.isTriggered()).isFalse();
    }

    @Test
    @DisplayName("should NOT trigger for same-location transactions (distance zero, speed zero)")
    void shouldNotTriggerForSameLocation() {
        when(geoLocationStore.getLastLocation(anyString()))
                .thenReturn(Optional.of(new double[]{CT_LAT, CT_LON, BASE_TIME.toEpochMilli()}));

        RuleEvaluationResult result = rule.evaluate(
                buildEvent(CT_LAT, CT_LON, BASE_TIME.plusSeconds(300)));

        assertThat(result.isTriggered()).isFalse();
    }

    private TransactionEvent buildEvent(double lat, double lon, Instant timestamp) {
        return TransactionEvent.builder()
                .transactionId("txn-travel-test")
                .userId("user-travel-001")
                .amount(new BigDecimal("100.00"))
                .merchant("Test Merchant")
                .category("TRAVEL")
                .latitude(lat)
                .longitude(lon)
                .timestamp(timestamp)
                .build();
    }
}
