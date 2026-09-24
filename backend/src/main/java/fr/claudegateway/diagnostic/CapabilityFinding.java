package fr.claudegateway.diagnostic;

import java.math.BigDecimal;
import java.util.List;

/**
 * <b>Un constat du diagnostic</b> (F-156 / SF-156-03) : ce qu'on conclut d'une capacité, et où
 * regarder.
 *
 * @param capabilityId la capacité
 * @param name         son nom lisible
 * @param verdict      active, dormante, ou indéterminée
 * @param why          ce qui fonde le verdict, en une phrase et avec son chiffre
 * @param where        les fichiers où la capacité vit — l'endroit où aller regarder
 * @param check        la condition qu'elle attend, donc ce qu'il faut vérifier
 * @param gainEur      le gain <b>calculé</b>, {@code null} quand il ne l'a pas été — jamais une
 *                     estimation
 */
public record CapabilityFinding(
        String capabilityId,
        String name,
        CapabilityVerdict verdict,
        String why,
        List<String> where,
        String check,
        BigDecimal gainEur) {

    /** Vrai pour ce que le diagnostic retient : ce qui dort, et ce qu'il n'a pas su trancher. */
    public boolean isFinding() {
        return verdict != CapabilityVerdict.ACTIVE;
    }
}
