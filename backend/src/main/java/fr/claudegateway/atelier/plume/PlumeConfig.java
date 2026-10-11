package fr.claudegateway.atelier.plume;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la liaison de {@link PlumeProperties} (F-193 / SF-193-01). */
@Configuration
@EnableConfigurationProperties(PlumeProperties.class)
class PlumeConfig {
}
