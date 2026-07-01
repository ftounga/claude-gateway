package fr.claudegateway.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active la planification Spring (`@Scheduled`) pour les traitements de fond de la plateforme
 * (F-05 : worker de polling OCR asynchrone). Les traitements lourds s'exécutent hors du thread HTTP.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
