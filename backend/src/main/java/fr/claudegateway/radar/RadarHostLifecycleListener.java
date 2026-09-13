package fr.claudegateway.radar;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.runner.host.RunnerHostLifecycleEvent;

/**
 * <b>Un Radar ne survit pas à son poste</b> (F-99 / SF-99-05).
 *
 * <p>À la suppression d'un poste, son Radar est purgé <b>dans la même transaction</b> : un poste
 * effacé ne laisse aucun extrait de communications derrière lui. Le périmètre est celui que le poste
 * annonce — propriétaire et identifiant —, jamais deviné. Contrairement à la gouvernance, une erreur
 * <b>remonte</b> : mieux vaut refuser la suppression du poste que laisser des extraits orphelins.</p>
 */
@Component
public class RadarHostLifecycleListener {

    private final RadarPurgeService purgeService;

    public RadarHostLifecycleListener(RadarPurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @EventListener
    @Transactional
    public void onHostLifecycle(RunnerHostLifecycleEvent event) {
        if (event.kind() == RunnerHostLifecycleEvent.Kind.DELETED) {
            purgeService.purge(new RadarScope(event.userId(), event.hostId()), RadarPurgeReason.HOST_DELETED);
        }
    }
}
