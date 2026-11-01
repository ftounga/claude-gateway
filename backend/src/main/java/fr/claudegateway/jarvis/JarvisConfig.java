package fr.claudegateway.jarvis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import fr.claudegateway.jarvis.stt.JarvisSttStreamProperties;

/**
 * Active la configuration de la voix de Jarvis (F-231 / SF-231-01, {@link JarvisVoiceProperties}) et de son écoute en
 * flux (F-233 / SF-233-04, {@link JarvisSttStreamProperties}).
 */
@Configuration
@EnableConfigurationProperties({ JarvisVoiceProperties.class, JarvisSttStreamProperties.class })
public class JarvisConfig {
}
