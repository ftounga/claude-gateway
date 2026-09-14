package fr.claudegateway.atelier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active les {@link AtelierProperties} de l'Atelier (F-28) et la compaction (F-117 / SF-117-01). */
@Configuration
@EnableConfigurationProperties({AtelierProperties.class, AtelierCompactionProperties.class})
public class AtelierConfig {
}
