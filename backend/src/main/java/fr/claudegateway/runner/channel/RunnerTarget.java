package fr.claudegateway.runner.channel;

import java.util.UUID;

/**
 * Cible d'un appel d'outil (F-48 / SF-48-01) : <b>quel poste</b> l'exécute, et <b>quel projet</b>
 * sous sa racine.
 *
 * <p>C'est la conséquence directe du déplacement de l'unité vers le poste. Le routage — registre,
 * socket, relais inter-pods — se fait par {@link #hostId} : c'est la machine qui est connectée.
 * Le projet, lui, voyage <b>par appel</b> : {@link #projectPath} part dans la trame
 * {@code tool_call}, et le runner referme son confinement dessus (SF-48-02, régime local — la
 * garantie reste celle du processus qui exécute, jamais celle du réseau).</p>
 *
 * <p>{@link #workspaceId} n'est pas transmis au runner : il ne sert qu'à la gateway, pour
 * l'isolation des appels en vol (annuler le tour d'un projet ne doit pas tuer celui d'un autre
 * projet du même poste) et pour que le journal dise <b>quel projet</b> a exécuté quoi.</p>
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
