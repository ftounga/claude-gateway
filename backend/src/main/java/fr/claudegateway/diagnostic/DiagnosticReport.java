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
 * @param sourceRead vrai quand le code a été lu et les constats enrichis (F-157 / SF-157-05)
 * @param sourceNote ce qui s'est passé côté lecture — notamment le refus quand le projet désigné
 *                   n'est pas le dépôt. <b>Un refus de lecture ne prive pas du diagnostic
 *                   gratuit</b> : le rapport est rendu quand même, avec ce mot
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
        List<String> specLines,
        boolean sourceRead,
        String sourceNote) {

    /** Forme d'avant la lecture du code (F-156), conservée pour les appelants qui l'attendent. */
    public DiagnosticReport(OffsetDateTime from, OffsetDateTime to, boolean truncated, int turns,
            int projects, BigDecimal costEur, List<CapabilityFinding> findings, int discarded,
            int active, List<ParityRow> parity, List<String> specLines) {
        this(from, to, truncated, turns, projects, costEur, findings, discarded, active, parity,
                specLines, false, null);
    }

    /** Le même rapport, avec ce que la lecture du code a donné. */
    public DiagnosticReport withSource(boolean read, String note) {
        return new DiagnosticReport(from, to, truncated, turns, projects, costEur, findings,
                discarded, active, parity, specLines, read, note);
    }

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
