package fr.claudegateway.atelier.agent;

import java.util.List;

/**
 * Résultat d'un run d'atelier ({@code AtelierSessionService.runTask}) (F-28 / Phase 2, ADR-013).
 *
 * <p>La consommation portée ici est celle du <b>tour</b> (F-30 SF-30-05) : ce sont exactement les
 * deltas décomptés du quota, pas le cumul de la session persistante. Une seule source de vérité —
 * recalculer ailleurs ouvrirait un écart entre ce que l'utilisateur voit et ce qui lui est facturé.
 * Le relevé étant best-effort, des valeurs à zéro signifient « inconnu » et ne sont pas affichées.</p>
 *
 * @param reply         réponse finale de l'agent
 * @param changedFiles  chemins (relatifs au workspace) des fichiers réécrits depuis les sorties
 * @param inputTokens   tokens d'entrée consommés par ce tour ({@code 0} si le relevé a échoué)
 * @param outputTokens  tokens de sortie consommés par ce tour ({@code 0} si le relevé a échoué)
 * @param activeSeconds secondes de bac à sable consommées par ce tour ({@code 0} si relevé échoué)
 * @param interrupted   vrai si le tour s'est arrêté sur une demande d'interruption (F-32 SF-32-01) :
 *                      il est conservé et décompté comme tout autre tour, mais l'écran doit le dire
 * @param budgetReached vrai si le tour s'est arrêté sur le <b>plafond de dépense</b> de la session
 *                      (F-36 SF-36-01). Distinct du quota mensuel épuisé : ce run-ci a atteint sa
 *                      borne, le suivant repartira d'un plafond neuf
 */
public record AtelierSessionResult(String reply, List<String> changedFiles, long inputTokens,
        long outputTokens, long activeSeconds, boolean interrupted, boolean budgetReached) {

    /** Résultat sans consommation connue (relevé best-effort en échec, ou run hors session). */
    public AtelierSessionResult(String reply, List<String> changedFiles) {
        this(reply, changedFiles, 0L, 0L, 0L, false, false);
    }

    /** Résultat d'un tour mené à son terme (jamais interrompu) : forme historique. */
    public AtelierSessionResult(String reply, List<String> changedFiles, long inputTokens,
            long outputTokens, long activeSeconds) {
        this(reply, changedFiles, inputTokens, outputTokens, activeSeconds, false, false);
    }

    /** Résultat d'un tour sans plafond atteint : forme d'avant F-36. */
    public AtelierSessionResult(String reply, List<String> changedFiles, long inputTokens,
            long outputTokens, long activeSeconds, boolean interrupted) {
        this(reply, changedFiles, inputTokens, outputTokens, activeSeconds, interrupted, false);
    }
}
