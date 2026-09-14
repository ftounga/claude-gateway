package fr.claudegateway.atelier.agent;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expire par âge les sessions Managed Agents laissées ouvertes (F-117 / SF-117-04).
 *
 * <p>L'audit mémoire l'a relevé : {@code agentSessionStartedAt} était stocké mais <b>jamais lu</b>, et
 * une session persistante (F-30 / SF-30-04) n'était terminée que par un {@code resetSession} explicite.
 * Un terminal hébergé laissé ouvert gardait donc son conteneur vivant sans limite. Ce balayage de fond
 * (hors du fil HTTP, règle async de {@code CLAUDE.md}) délègue à
 * {@link AtelierSessionService#reapExpiredSessions(OffsetDateTime)}, qui termine chaque session
 * fournisseur (best-effort) et efface son identifiant.</p>
 *
 * <p>Aucune logique ici : réglages en {@link Value} pour ne pas toucher au constructeur des
 * {@link AtelierAgentProperties}. Désactivable par {@code app.atelier.agent.reaper.enabled=false}
 * (tests, et coupe-circuit).</p>
 */
@Component
@ConditionalOnProperty(name = "app.atelier.agent.reaper.enabled", havingValue = "true",
        matchIfMissing = true)
public class ManagedAgentSessionReaper {

    private static final Logger log = LoggerFactory.getLogger(ManagedAgentSessionReaper.class);

    private final AtelierSessionService sessionService;
    private final Duration maxAge;

    public ManagedAgentSessionReaper(AtelierSessionService sessionService,
            @Value("${app.atelier.agent.reaper.max-age:PT24H}") Duration maxAge) {
        this.sessionService = sessionService;
        // Un âge absent, nul ou négatif retomberait sur un balayage qui expire TOUT : garde-fou.
        this.maxAge = maxAge == null || maxAge.isZero() || maxAge.isNegative()
                ? Duration.ofHours(24) : maxAge;
    }

    @Scheduled(cron = "${app.atelier.agent.reaper.cron:0 20 * * * *}")
    public void reap() {
        try {
            sessionService.reapExpiredSessions(OffsetDateTime.now().minus(maxAge));
        } catch (RuntimeException ex) {
            // Le planificateur ne s'arrête jamais sur une erreur ponctuelle. Message neutre.
            log.warn("Reaper Managed Agents : balayage interrompu ({})", ex.getClass().getSimpleName());
        }
    }
}
