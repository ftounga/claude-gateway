package fr.claudegateway.upload;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active la liaison des propriétés de téléversement ({@link UploadProperties}).
 */
@Configuration
@EnableConfigurationProperties(UploadProperties.class)
public class UploadConfig {
}
