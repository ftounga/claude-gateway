package fr.claudegateway.bilan;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * <b>Le relevé d'une session</b> (F-155 / SF-155-01) : ce qui a été fait, ce que ça a coûté, et où
 * l'argent est parti.
 *
 * <p><b>Il ne juge rien.</b> Il compte. Les suggestions viennent après (SF-155-02) — et les mélanger
 * empêcherait de vérifier les chiffres sans appeler un modèle.</p>
 *
 * @param from           début de la fenêtre observée
 * @param to             fin de la fenêtre observée
 * @param turns          nombre de tours facturés
 * @param elapsed        du premier au dernier tour — le temps <b>réel</b>, pas la somme des durées
 * @param toolCalls      appels d'outils
 * @param failedTools    appels d'outils en échec
 * @param filesWritten   fichiers <b>distincts</b> écrits
 * @param costEur        coût, converti au taux d'affichage configuré
 * @param inputTokens    jetons d'entrée facturés plein tarif
 * @param outputTokens   jetons de sortie
 * @param cacheReadTokens  jetons lus en cache — le levier de F-134
 * @param cacheWriteTokens jetons écrits en cache
 * @param cacheShare     part du cache dans l'entrée totale, de 0 à 100
 * @param turnsWithoutCost tours dont le coût fournisseur est inconnu — dit, sinon le total paraîtrait faux
 * @param costliestTurns les trois tours les plus chers
 * @param heaviestTools  les trois outils les plus lourds
 */
public record SessionLedger(
        OffsetDateTime from,
        OffsetDateTime to,
        int turns,
        Duration elapsed,
        int toolCalls,
        int failedTools,
        int filesWritten,
        BigDecimal costEur,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        int cacheShare,
        int turnsWithoutCost,
        List<CostlyTurn> costliestTurns,
        List<HeavyTool> heaviestTools) {

    /** Un relevé sans rien dedans — une session sans activité n'est pas une erreur. */
    public static SessionLedger empty(OffsetDateTime from, OffsetDateTime to) {
        return new SessionLedger(from, to, 0, Duration.ZERO, 0, 0, 0, BigDecimal.ZERO,
                0, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    /** Vrai quand il n'y a rien à dire : ni tour, ni appel d'outil. */
    public boolean isEmpty() {
        return turns == 0 && toolCalls == 0;
    }

    /** Un tour cher : de quoi le retrouver et comprendre pourquoi il a coûté. */
    public record CostlyTurn(OffsetDateTime occurredAt, String model, BigDecimal costEur,
                             long inputTokens, long outputTokens, long cacheReadTokens) {
    }

    /** Un outil lourd : combien d'appels, combien de temps, combien d'échecs. */
    public record HeavyTool(String tool, int calls, Duration total, int failures) {
    }
}
