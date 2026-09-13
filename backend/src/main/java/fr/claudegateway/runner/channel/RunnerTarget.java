package fr.claudegateway.runner.channel;

import java.util.UUID;

/**
 * Cible d'un appel d'outil (F-48 / SF-48-01) : <b>quel poste</b> l'exécute, et <b>quel projet</b>
 * sous sa racine.
 *
 * <p>C'est la conséquence directe du déplacement de l'unité vers le poste. Le routage — registre,
 * socket, relais inter-pods — se fait par {@link #hostId} : c'est la machine qui est connectée.
 * Le projet, lui, voyage <b>par appel</b> : {@link #projectPath} part dans la trame
 * {@code tool_call} et donne le <b>dossier de départ</b> du tour (SF-48-02 ; ce n'est plus une
 * borne depuis F-73 / SF-73-01 — ce qui s'interpose est la porte de confirmation).</p>
 *
 * <p>{@link #workspaceId} n'est pas transmis au runner : il ne sert qu'à la gateway, pour
 * l'isolation des appels en vol (annuler le tour d'un projet ne doit pas tuer celui d'un autre
 * projet du même poste) et pour que le journal dise <b>quel projet</b> a exécuté quoi.</p>
 *
 * <p><b>Une exception, nommée</b> (F-90 / SF-90-03) : les deux outils de captures de réunion
 * ({@code teams_meeting_moments}, {@code teams_moments_status}) le reçoivent, parce qu'ils font
 * <b>remonter</b> des images et que la machine doit savoir dans quel terminal Teams les déposer.
 * Cela ne déplace <b>aucune</b> garde : la route de dépôt revérifie que ce terminal appartient au
 * compte du jeton présenté, si bien que l'isolation {@code user_id} tient sans dépendre de ce que le
 * runner affirme. L'exception est restreinte à ces deux outils, dans
 * {@code RunnerToolGateway.teamsRead}.</p>
 *
 * @param hostId      poste qui exécute ; clef de tout le routage
 * @param workspaceId projet concerné ; jamais transmis au runner
 * @param projectPath chemin relatif du projet sous la racine du poste ; {@code ""} = la racine
 */
public record RunnerTarget(UUID hostId, UUID workspaceId, String projectPath) {

    /** Chemin relatif, jamais {@code null} — la chaîne vide désigne la racine du poste. */
    public String safeProjectPath() {
        return projectPath == null ? "" : projectPath;
    }

    /** Vrai si la cible est exploitable telle quelle (garde d'entrée du relais). */
    public boolean isValid() {
        return hostId != null && workspaceId != null;
    }
}
