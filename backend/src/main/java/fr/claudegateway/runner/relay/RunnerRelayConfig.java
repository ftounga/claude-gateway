package fr.claudegateway.runner.relay;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Câblage du relais interne (F-38 / SF-38-12).
 *
 * <p>Les propriétés sont toujours liées — {@link RunnerCallRouter} en a besoin pour sa garde
 * anti-auto-appel, même quand le relais est éteint. Tout le reste (connecteur supplémentaire,
 * filtre de garde) n'existe que si le secret est configuré : voir
 * {@link RunnerRelayEnabledCondition}.</p>
 */
@Configuration
@EnableConfigurationProperties(RunnerRelayProperties.class)
public class RunnerRelayConfig {

    /** Beans publiés uniquement quand {@code app.runner.relay.secret} est renseigné. */
    @Configuration(proxyBeanMethods = false)
    @Conditional(RunnerRelayEnabledCondition.class)
    static class RelayActiveConfiguration {

        @Bean
        RunnerRelayConnectorCustomizer runnerRelayConnectorCustomizer(RunnerRelayProperties properties) {
            return new RunnerRelayConnectorCustomizer(properties.getPort());
        }

        /**
         * Le filtre est enregistré en {@code HIGHEST_PRECEDENCE}, donc <b>avant</b> la chaîne Spring
         * Security : le 404 « mauvais port » et le 401 « secret absent » sont rendus sans qu'aucun
         * autre composant n'ait vu la requête.
         */
        @Bean
        FilterRegistrationBean<RunnerRelayAuthFilter> runnerRelayAuthFilterRegistration(
                RunnerRelayConnectorCustomizer connector, RunnerRelayProperties properties) {
            FilterRegistrationBean<RunnerRelayAuthFilter> registration = new FilterRegistrationBean<>(
                    new RunnerRelayAuthFilter(connector, properties.getSecret()));
            registration.addUrlPatterns("/internal/*");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setName("runnerRelayAuthFilter");
            return registration;
        }
    }
}
