package fr.claudegateway.atelier.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active les réglages des Managed Agents de l'Atelier : {@link AtelierAgentProperties} (F-28 /
 * Phase 2), {@link AtelierCostProperties} (F-36 : plafond de dépense et tarifs de référence) et
 * {@link AtelierDiffProperties} (F-37 : bornes du diff des modifications d'un tour).
 */
@Configuration
@EnableConfigurationProperties({AtelierAgentProperties.class, AtelierCostProperties.class,
        AtelierDiffProperties.class})
public class AtelierAgentConfiguration {
}
