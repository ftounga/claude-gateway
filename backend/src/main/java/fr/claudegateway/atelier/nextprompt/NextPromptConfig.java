package fr.claudegateway.atelier.nextprompt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la liaison de {@link NextPromptProperties} (F-144 / SF-144-02). */
@Configuration
@EnableConfigurationProperties(NextPromptProperties.class)
class NextPromptConfig {
}
