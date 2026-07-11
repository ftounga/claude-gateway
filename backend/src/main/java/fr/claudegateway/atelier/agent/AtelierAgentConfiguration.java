package fr.claudegateway.atelier.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active les {@link AtelierAgentProperties} des Managed Agents de l'Atelier (F-28 / Phase 2). */
@Configuration
@EnableConfigurationProperties(AtelierAgentProperties.class)
public class AtelierAgentConfiguration {
}
