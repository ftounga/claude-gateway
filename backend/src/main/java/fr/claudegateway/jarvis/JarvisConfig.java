package fr.claudegateway.jarvis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la configuration de la voix de Jarvis (F-231 / SF-231-01). Voir {@link JarvisVoiceProperties}. */
@Configuration
@EnableConfigurationProperties(JarvisVoiceProperties.class)
public class JarvisConfig {
}
