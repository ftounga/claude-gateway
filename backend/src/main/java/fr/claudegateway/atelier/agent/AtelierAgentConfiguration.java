package fr.claudegateway.atelier.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active les réglages des Managed Agents de l'Atelier : {@link AtelierAgentProperties} (F-28 /
 * Phase 2) et {@link AtelierCostProperties} (F-36 : plafond de dépense et tarifs de référence).
 */
@Configuration
@EnableConfigurationProperties({AtelierAgentProperties.class, AtelierCostProperties.class})
public class AtelierAgentConfiguration {
}
