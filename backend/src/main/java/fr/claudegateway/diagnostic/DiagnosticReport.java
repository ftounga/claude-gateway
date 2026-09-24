package fr.claudegateway.diagnostic;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * <b>Le rapport de diagnostic</b> (F-156 / SF-156-05) : ce que la période dit du produit, et ce
 * qu'il faudrait en faire.
 *
 * @param from       début de la période observée
 * @param to         fin
 * @param truncated  vrai quand la durée demandée a été ramenée aux bornes — le dire fait partie du
 *                   résultat, sinon le dénominateur mentirait
 * @param turns      tours de la période
 * @param projects   projets actifs
 * @param costEur    coût de la période — le dénominateur, sans lequel aucun constat n'est rapportable
 * @param findings   les constats retenus
 * @param discarded  combien ont été écartés parce que leur gain calculé est sous le seuil
 * @param active     combien de capacités tournent — comptées, pas listées
 * @param parity     la table de parité
 * @param specLines  les lignes prêtes à coller dans {@code PRODUCT_SPEC.md}, au statut
 *                   {@code Candidate} : <b>l'application propose, le PO décide</b>
 */
public record DiagnosticReport(
        OffsetDateTime from,
        OffsetDateTime to,
        boolean truncated,
        int turns,
        int projects,
        BigDecimal costEur,
        List<CapabilityFinding> findings,
        int discarded,
        int active,
        List<ParityRow> parity,
        List<String> specLines) {

    /** Un rapport sans matière — ce n'est pas une erreur, et rien n'est inventé. */
    public static DiagnosticReport nothingToObserve(OffsetDateTime from, OffsetDateTime to,
                                                    List<ParityRow> parity) {
        return new DiagnosticReport(from, to, false, 0, 0, BigDecimal.ZERO, List.of(), 0, 0,
                parity, List.of());
    }

    /** « Rien à signaler » — une conclusion valide, pas un échec. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isClean() {
        return findings.isEmpty();
    }
}
