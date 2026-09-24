package fr.claudegateway.diagnostic;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Le diagnostic du produit (F-156, F-157) : active les réglages de la lecture raisonnée.
 *
 * <p>Les plafonds sont là pour être <b>baissés</b> : la lecture raisonnée est la seule partie du
 * diagnostic qui consomme des jetons.</p>
 */
@Configuration
@EnableConfigurationProperties(DiagnosticProperties.class)
public class DiagnosticConfig {
}
