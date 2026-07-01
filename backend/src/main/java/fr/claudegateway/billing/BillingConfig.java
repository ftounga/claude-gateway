package fr.claudegateway.billing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active la liaison des propriétés du module billing ({@link BillingProperties}).
 */
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfig {
}
