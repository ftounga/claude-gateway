package fr.claudegateway.help;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la liaison des réglages du chatbot d'aide produit (F-54 / SF-54-01). */
@Configuration
@EnableConfigurationProperties(HelpProperties.class)
public class HelpConfig {
}
