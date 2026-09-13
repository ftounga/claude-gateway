package fr.claudegateway.pages;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la liaison des bornes des pages (F-109). */
@Configuration
@EnableConfigurationProperties(PageLimits.class)
public class PagesConfig {
}
