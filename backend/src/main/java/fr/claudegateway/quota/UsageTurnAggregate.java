package fr.claudegateway.quota;

import java.util.UUID;

/**
 * Somme des tours d'une fenêtre pour un couple (poste, projet) — projection de lecture de
 * {@link UsageTurnRepository} (F-61 / SF-61-02).
 *
 * <p>Les deux identifiants peuvent être {@code null} : un tour hors projet ({@code /chat},
 * {@code /ask}) n'a ni l'un ni l'autre, et un projet en bac à sable n'a pas de poste. Ces tours
 * sont <b>comptés</b> malgré tout — sans eux, la somme des clients ne se réconcilierait pas avec le
 * total de la période, et un écran de refacturation dont les lignes ne font pas le total n'est pas
 * utilisable.</p>
 *
 * <p>Entrée et sortie restent <b>séparées</b> jusqu'à l'affichage : leurs coûts unitaires n'ont
 * rien à voir, et un total unique interdirait toute estimation juste.</p>
 *
 * <p>Projection de lecture, pas une entité : elle ne porte aucun contenu et ne sort jamais telle
 * quelle de la gateway.</p>
 */
public interface UsageTurnAggregate {

    /** Poste du tour (instantané au moment du tour), ou {@code null} — « hors client ». */
    UUID getHostId();

    /** Projet du tour, ou {@code null} — tour hors projet. */
    UUID getWorkspaceId();

    /** Somme des tokens d'entrée de la fenêtre pour ce couple. */
    long getInputTokens();

    /** Somme des tokens de sortie de la fenêtre pour ce couple. */
    long getOutputTokens();

    /** Total de la ligne (entrée + sortie) — pour le tri et l'affichage, jamais pour tarifer. */
    default long total() {
        return getInputTokens() + getOutputTokens();
    }
}
