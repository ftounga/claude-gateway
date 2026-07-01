package fr.claudegateway.quota;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Active la liaison des propriétés du module quota ({@link QuotaProperties}) et expose l'horloge
 * système (UTC) utilisée pour borner la période de facturation. L'horloge est injectable afin de
 * rendre le calcul de période testable.
 */
@Configuration
@EnableConfigurationProperties({QuotaProperties.class, UsageReportProperties.class})
public class QuotaConfig {

    /** Horloge par défaut de la plateforme (UTC), surchargée dans les tests si besoin. */
    @Bean
    @Primary
    public Clock clock() {
        return Clock.systemUTC();
    }
}
