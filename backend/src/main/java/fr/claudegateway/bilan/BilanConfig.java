package fr.claudegateway.bilan;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Le bilan de session (F-155) : active ses réglages.
 *
 * <p>Le seuil d'impact est <b>configurable</b> — pour être relevé quand le bilan deviendra trop
 * bavard, jamais abaissé pour faire nombre.</p>
 */
@Configuration
@EnableConfigurationProperties(SessionBilanProperties.class)
public class BilanConfig {
}
