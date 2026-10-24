package fr.claudegateway.speech;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la configuration de la voix off (F-215 / SF-215-01). Voir {@link SpeechProperties}. */
@Configuration
@EnableConfigurationProperties(SpeechProperties.class)
public class SpeechConfig {
}
