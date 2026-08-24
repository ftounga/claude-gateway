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
 */
public record AtelierSessionResult(String reply, List<String> changedFiles, long inputTokens,
        long outputTokens, long activeSeconds) {

    /** Résultat sans consommation connue (relevé best-effort en échec, ou run hors session). */
    public AtelierSessionResult(String reply, List<String> changedFiles) {
        this(reply, changedFiles, 0L, 0L, 0L);
    }
}
