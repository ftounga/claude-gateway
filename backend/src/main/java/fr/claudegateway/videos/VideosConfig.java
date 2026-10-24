package fr.claudegateway.videos;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la liaison des bornes des vidéos (F-213). */
@Configuration
@EnableConfigurationProperties(VideoLimits.class)
public class VideosConfig {
}
