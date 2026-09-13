package fr.claudegateway.radar.analysis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active les réglages de l'analyse du Radar (F-101). */
@Configuration
@EnableConfigurationProperties(RadarAnalysisProperties.class)
public class RadarAnalysisConfig {
}
