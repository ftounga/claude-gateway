package fr.claudegateway.speech.elevenlabs;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la configuration ElevenLabs (F-233). Voir {@link ElevenLabsProperties}. */
@Configuration
@EnableConfigurationProperties(ElevenLabsProperties.class)
public class ElevenLabsConfig {
}
