package com.fraud.detection.properties;

import com.fraud.detection.properties.rules.Duplicate;
import com.fraud.detection.properties.rules.GeoAnomaly;
import com.fraud.detection.properties.rules.HighAmount;
import com.fraud.detection.properties.rules.ImpossibleTravel;
import com.fraud.detection.properties.rules.Velocity;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Strongly-typed configuration for all fraud rules, grouped by rule.
 *
 * Binds the hierarchical {@code fraud.rules.*} configuration, e.g.:
 * <pre>
 * fraud:
 *   rules:
 *     velocity:
 *       threshold: 10
 *       window-seconds: 300
 *       score: 40
 * </pre>
 *
 * Each rule reads only its own group (e.g. {@code properties.getVelocity()}),
 * keeping rule configuration cohesive and self-documenting.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fraud.rules")
public class FraudRuleProperties {

    private Velocity velocity;
    private HighAmount highAmount;
    private GeoAnomaly geoAnomaly;
    private Duplicate duplicate;
    private ImpossibleTravel impossibleTravel;
}
