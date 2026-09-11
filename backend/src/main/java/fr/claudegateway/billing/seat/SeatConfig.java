package fr.claudegateway.billing.seat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Active la liaison des propriétés du supplément par poste (F-65 / SF-65-01). Aucune valeur
 * commerciale n'est écrite dans le code : elles vivent toutes dans {@link SeatProperties}, et leurs
 * défauts rendent le mécanisme inerte tant que le PO n'a rien configuré.
 */
@Configuration
@EnableConfigurationProperties(SeatProperties.class)
public class SeatConfig {
}
