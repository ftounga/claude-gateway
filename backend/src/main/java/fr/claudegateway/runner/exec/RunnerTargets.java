package fr.claudegateway.runner.exec;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * Construit la cible d'exécution d'un projet (F-48 / SF-48-01) : le <b>poste</b> auquel il est
 * rattaché, et son chemin sous la racine de ce poste.
 *
 * <p>Un seul endroit pour cette traduction. Elle est faite <b>à partir de l'entité chargée</b>, donc
 * après le contrôle d'appartenance ({@code requireOwned}) : jamais à partir d'un identifiant venu du
 * client.</p>
 */
public final class RunnerTargets {

    private RunnerTargets() {
    }

    /**
     * Cible d'un projet. Le poste peut être {@code null} — un projet qu'on vient de créer n'est
     * rattaché à rien —, auquel cas le routeur rend {@code runner_unavailable} plutôt qu'une
     * exception : c'est un état réparable, pas une panne.
     */
    public static RunnerTarget of(Workspace workspace) {
        return new RunnerTarget(workspace.getHostId(), workspace.getId(),
                workspace.getProjectPath() == null ? "" : workspace.getProjectPath());
    }
}
