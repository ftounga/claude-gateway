package fr.claudegateway.diagnostic;

import java.math.BigDecimal;

/**
 * <b>Ce qu'une période dit d'une capacité</b> (F-156 / SF-156-02).
 *
 * <p>Le cas le plus intéressant est celui où {@code hits} vaut <b>zéro</b> : la capacité existe et
 * ne s'est jamais déclenchée. C'est un résultat, pas une absence de résultat.</p>
 *
 * @param capabilityId    la capacité observée
 * @param name            son nom lisible
 * @param hits            combien de fois son signal a été vu sur la période
 * @param projectsWithSignal dans combien de projets
 * @param projectsObserved   sur combien de projets actifs
 * @param wasteEur        le coût du gaspillage <b>quand il se calcule</b>, {@code null} sinon —
 *                        un montant inventé est exactement ce que le seuil d'impact interdit
 * @param measurable      faux quand le signal n'est pas mesurable à ce stade : le verdict attend
 *                        SF-156-03, qui saura lire les tables et le code
 */
public record CapabilityObservation(
        String capabilityId,
        String name,
        long hits,
        int projectsWithSignal,
        int projectsObserved,
        BigDecimal wasteEur,
        boolean measurable) {

    /** Vrai quand la capacité ne s'est <b>jamais</b> déclenchée sur la période. */
    public boolean neverFired() {
        return measurable && hits == 0;
    }
}
