package fr.claudegateway.git;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active la liaison des propriétés Git/GitHub ({@link GitProperties}). La {@code RestClient.Builder}
 * est fournie par l'auto-configuration Spring Boot.
 */
@Configuration
@EnableConfigurationProperties(GitProperties.class)
public class GitConfig {
}
