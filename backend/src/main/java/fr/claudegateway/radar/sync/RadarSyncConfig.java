package fr.claudegateway.radar.sync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active les réglages de la synchro du soir (F-100). */
@Configuration
@EnableConfigurationProperties(RadarSyncProperties.class)
public class RadarSyncConfig {
}
