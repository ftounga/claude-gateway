package fr.claudegateway.atelier.veille;

import java.time.OffsetDateTime;
import java.util.List;

import fr.claudegateway.atelier.Workspace;

/**
 * <b>Le registre d'état vu des veilles</b> (F-247 / SF-247-07) : la garde d'un relevé avant qu'il parte, et le
 * rangement des instantanés en faits (relevés et veilles existantes). Implémenté par le registre ; sans lui, aucun
 * relevé ne part et les veilles d'alerte se comportent exactement comme avant.
 */
public interface JarvisWatchStateHook {

    /** Un relevé peut-il partir maintenant (coupe-circuits, réglage du client, runner capable, poste inactif) ? */
    boolean mayRun(JarvisWatch watch, Workspace workspace, OffsetDateTime now);

    /** Range l'instantané d'un passage réussi en faits datés de {@code observedAt}. Ne lève jamais. */
    void record(JarvisWatch watch, Workspace workspace, List<JarvisWatchItems.Item> items, OffsetDateTime observedAt);
}
