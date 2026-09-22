package fr.claudegateway.images;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active la configuration du fournisseur d'images (F-142 / SF-142-04).
 * Éteint par défaut : voir {@link ImageGenerationProperties}.
 */
@Configuration
@EnableConfigurationProperties(ImageGenerationProperties.class)
public class ImageConfig {
}
