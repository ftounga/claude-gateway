package fr.claudegateway.runner.host.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Vue d'ensemble d'un <b>poste</b> (F-49 / SF-49-01) : tout ce qu'un écran doit en savoir, en une
 * seule ligne de réponse.
 *
 * <p>Elle réunit ce qui vivait jusqu'ici à trois endroits — l'état du runner, la liste des projets,
 * et le journal de chacun. Un écran qui montre plusieurs postes n'a plus à jouer trois familles
 * d'appels par rafraîchissement.</p>
 *
 * <p>Le « depuis quand » n'est pas calculé ici : la gateway rend des <b>instants</b>
 * ({@code lastSeenAt}, {@code lastActivityAt}) et l'écran en fait une durée. Une durée calculée au
 * serveur vieillit dans le navigateur.</p>
 *
 * @param connected      vrai si un runner de ce poste est joignable maintenant, tous replicas confondus
 * @param lastActivityAt dernière activité observée sur le poste, tous projets confondus
 * @param activeProjects nombre de projets actifs maintenant — « ce qui tourne »
 * @param projects       les projets rangés sous ce poste, les plus actifs d'abord
 */
public record RunnerHostOverviewResponse(
        UUID id,
        String name,
        String rootName,
        String os,
        String shell,
        Boolean elevated,
        boolean connected,
        OffsetDateTime lastSeenAt,
        OffsetDateTime createdAt,
        OffsetDateTime lastActivityAt,
        int activeProjects,
        List<HostProjectSummary> projects) {

    /**
     * Un projet vu depuis son poste (F-49 / SF-49-01).
     *
     * <p>{@code lastTool} est le <b>nom</b> du dernier outil employé ({@code bash}, {@code read}…),
     * jamais sa cible : savoir qu'un {@code bash} a tourné suffit à une vue d'état, et la commande
     * elle-même reste derrière l'écran du journal du projet plutôt que d'être recopiée dans une vue
     * rafraîchie toutes les quinze secondes.</p>
     *
     * @param projectPath      chemin du projet <b>sous la racine du poste</b>, ou {@code null} pour la racine
     * @param executionTarget  {@code RUNNER} ou {@code SANDBOX}
     * @param calls            appels journalisés sur la fenêtre observée
     * @param active           vrai si la dernière activité est plus récente que la fenêtre d'activité
     */
    public record HostProjectSummary(
            UUID id,
            String name,
            String projectPath,
            String executionTarget,
            OffsetDateTime lastActivityAt,
            String lastTool,
            long calls,
            boolean active) {
    }
}
