package fr.claudegateway.ask;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active la liaison des réglages du Q&A documentaire (F-07).
 */
@Configuration
@EnableConfigurationProperties(AskProperties.class)
public class AskConfig {
}
