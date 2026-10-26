package fr.claudegateway.quota;

import java.util.UUID;

/**
 * <b>Être prévenu d'un relevé inscrit au journal d'usage</b> (F-216 / SF-216-02) — par exemple pour imputer le coût
 * d'un tour au devis d'une vidéo en cours de fabrication.
 *
 * <p>Appelé <b>après</b> l'écriture de la ligne, hors de sa transaction. Une erreur levée ici est avalée par
 * {@link UsageLedgerService} : un écouteur ne peut jamais faire échouer un relevé, ni le tour qui l'a produit.</p>
 */
public interface UsageRecordedListener {

    /**
     * Un relevé vient d'être inscrit.
     *
     * @param userId      compte du relevé
     * @param workspaceId sujet du relevé, ou {@code null}
     * @param cost        coût réel établi, ou {@code null}
     */
    void onRecorded(UUID userId, UUID workspaceId, TurnCost cost);
}
