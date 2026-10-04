package fr.claudegateway.governance.map.index;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Branche les réglages de l'index de la carte (F-174). */
@Configuration
@EnableConfigurationProperties(HostMapIndexProperties.class)
class HostMapIndexConfig {
}
