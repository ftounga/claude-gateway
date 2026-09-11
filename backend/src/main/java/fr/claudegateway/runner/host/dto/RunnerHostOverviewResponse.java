package fr.claudegateway.runner.host.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.runner.host.HostMissionStatus;

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
 * <p><b>Les clôturés y sont</b> (F-60 / SF-60-01, arbitrage n° 3) : la gateway rend l'état de
 * chaque poste, y compris {@code CLOSED}, et ne filtre rien. « Se ranger sans disparaître » est une
 * exigence d'écran ; un filtre au serveur obligerait à un second appel ou à un paramètre pour rendre
 * les missions closes consultables — donc deux états de vue à synchroniser sur un écran qui se
 * rafraîchit toutes les quinze secondes.</p>
 *
 * <p><b>Le poste « Hébergé »</b> (F-71 / SF-71-01) emprunte cette même forme, avec {@code id} nul et
 * {@link #virtual} vrai : il regroupe les projets sans machine — dépôt GitHub, archive importée —
 * que l'accueil, organisé par postes depuis F-68, n'avait nulle part où ranger. <b>Aucune ligne
 * n'existe en base pour lui</b> : c'est une vue. Son {@code id} est nul <b>par choix</b>, et non par
 * omission : un identifiant constant ressemblerait à une entité et finirait envoyé à un endpoint qui
 * répondrait 404. Nul, il est inexploitable par construction.</p>
 *
 * @param virtual        vrai pour le poste « Hébergé » : ni appairage, ni runner, ni suppression
 * @param connected      vrai si un runner de ce poste est joignable maintenant, tous replicas confondus
 * @param missionStatus  état de mission <b>déclaré</b> par le propriétaire, indépendant de {@code connected}
 * @param lastActivityAt dernière activité observée sur le poste, tous projets confondus
 * @param activeProjects nombre de projets actifs maintenant — « ce qui tourne »
 * @param liveTerminals  nombre de terminaux <b>vivants</b> sur les projets de ce poste (F-70)
 * @param projects       les projets rangés sous ce poste, les plus actifs d'abord
 */
public record RunnerHostOverviewResponse(
        UUID id,
        String name,
        String rootName,
        String os,
        String shell,
        Boolean elevated,
        boolean virtual,
        boolean connected,
        HostMissionStatus missionStatus,
        OffsetDateTime lastSeenAt,
        OffsetDateTime createdAt,
        OffsetDateTime lastActivityAt,
        int activeProjects,
        int liveTerminals,
        List<HostProjectSummary> projects) {

    /** Nom du poste virtuel, écrit <b>par la gateway</b> : deux écrans qui le nommeraient chacun à
     * leur façon seraient deux vérités. */
    public static final String HOSTED_NAME = "Hébergé";

    /**
     * Le poste <b>virtuel</b> « Hébergé » (F-71 / SF-71-01).
     *
     * <p>Tout ce qui décrit une machine est nul : pas de racine, pas de système, pas
     * d'interpréteur, jamais vu, jamais créé. {@code missionStatus} l'est aussi — une mission se
     * déclare sur un client, et il n'y a ici ni client ni machine.</p>
     *
     * <p>{@code activeProjects} et {@code lastActivityAt} restent à zéro et à nul : « ce qui
     * tourne » se lit dans le <b>journal du runner</b>, qui n'a rien à dire d'un projet qu'aucun
     * runner n'exécute. Le signe de vie, lui, est exact et rendu — un terminal ouvert sur un projet
     * hébergé est un terminal ouvert.</p>
     */
    public static RunnerHostOverviewResponse hosted(List<HostProjectSummary> projects,
            int liveTerminals) {
        return new RunnerHostOverviewResponse(null, HOSTED_NAME, null, null, null, null, true,
                false, null, null, null, null, 0, liveTerminals, projects);
    }

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
     * @param liveTerminal     vrai si un terminal de <b>cet utilisateur</b> est ouvert sur ce projet
     *                         maintenant (F-70 / SF-70-01) — un onglet vivant, pas un tour en cours
     */
    public record HostProjectSummary(
            UUID id,
            String name,
            String projectPath,
            String executionTarget,
            OffsetDateTime lastActivityAt,
            String lastTool,
            long calls,
            boolean active,
            boolean liveTerminal) {
    }
}
