package fr.claudegateway.push;

import java.util.UUID;

/**
 * <b>Le journal des notifications</b> (F-185 / SF-185-04) : ce que l'émetteur inscrit pour le centre
 * de notifications, avant même de savoir s'il y a un appareil à faire sonner. Une interface plutôt
 * qu'une dépendance : le push n'a pas à connaître la table ni les terminaux.
 */
public interface NotificationJournal {

    /**
     * Inscrit l'événement pour ce compte. {@code alreadySeen} : le terminal était regardé, la ligne
     * naît lue.
     */
    void record(UUID userId, UUID workspaceId, PushEvent event, boolean alreadySeen);

    /** Aucun journal (tests, contexte sans centre). */
    NotificationJournal NONE = (userId, workspaceId, event, alreadySeen) -> { };
}
