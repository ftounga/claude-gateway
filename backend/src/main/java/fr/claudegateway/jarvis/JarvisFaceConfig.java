package fr.claudegateway.jarvis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la configuration du visage de Jarvis (F-250, {@link JarvisFaceProperties}). */
@Configuration
@EnableConfigurationProperties(JarvisFaceProperties.class)
public class JarvisFaceConfig {
}
