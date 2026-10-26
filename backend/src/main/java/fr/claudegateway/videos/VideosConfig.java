package fr.claudegateway.videos;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la liaison des bornes des vidéos (F-213) et des coefficients du devis (F-216). */
@Configuration
@EnableConfigurationProperties({VideoLimits.class, VideoCostProperties.class})
public class VideosConfig {
}
