package fr.claudegateway.diagrams;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active les réglages du rendu de diagrammes (F-142 / SF-142-06). */
@Configuration
@EnableConfigurationProperties(DiagramProperties.class)
public class DiagramConfig {
}
