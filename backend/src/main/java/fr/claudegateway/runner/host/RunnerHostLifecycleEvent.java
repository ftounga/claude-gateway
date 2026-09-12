package fr.claudegateway.runner.host;

import java.util.UUID;

/**
 * Un poste vient de naître, ou de disparaître (F-75 / SF-75-01).
 *
 * <p><b>Pourquoi un événement plutôt qu'un appel direct.</b> Depuis F-75, la gouvernance s'active sur
 * un <b>poste</b> : elle doit donc embarquer la sélection par défaut quand un poste naît, et oublier
 * ses activations quand il disparaît. Le module gouvernance dépend déjà du module poste ; l'appeler
 * en retour créerait un cycle d'injection que Spring refuserait au démarrage. L'événement rend la
 * dépendance à sens unique : le poste <i>annonce</i>, la gouvernance <i>écoute</i>. C'est exactement
 * le procédé retenu par F-51 pour la création d'un projet.</p>
 *
 * @param userId propriétaire du poste (isolation — jamais deviné par l'auditeur)
 * @param hostId poste concerné
 * @param kind   ce qui vient de lui arriver
 */
public record RunnerHostLifecycleEvent(UUID userId, UUID hostId, Kind kind) {

    /** Ce qui est arrivé au poste. Deux moments, et deux seulement. */
    public enum Kind {

        /** Le poste vient d'être créé : il embarque ce qui est marqué « appliqué par défaut ». */
        CREATED,

        /** Le poste vient d'être supprimé : sa gouvernance ne lui survit pas. */
        DELETED
    }

    public static RunnerHostLifecycleEvent created(UUID userId, UUID hostId) {
        return new RunnerHostLifecycleEvent(userId, hostId, Kind.CREATED);
    }

    public static RunnerHostLifecycleEvent deleted(UUID userId, UUID hostId) {
        return new RunnerHostLifecycleEvent(userId, hostId, Kind.DELETED);
    }
}
