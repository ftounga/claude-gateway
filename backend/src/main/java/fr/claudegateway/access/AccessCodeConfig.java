package fr.claudegateway.access;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la configuration des codes d'accès à durée limitée (F-62). */
@Configuration
@EnableConfigurationProperties(AccessCodeProperties.class)
public class AccessCodeConfig {
}
