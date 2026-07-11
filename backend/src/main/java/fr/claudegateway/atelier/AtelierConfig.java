package fr.claudegateway.atelier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active les {@link AtelierProperties} de l'Atelier (F-28). */
@Configuration
@EnableConfigurationProperties(AtelierProperties.class)
public class AtelierConfig {
}
