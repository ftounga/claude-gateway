package fr.claudegateway.atelier;

/**
 * Opération refusée parce qu'elle est incompatible avec la <b>cible d'exécution</b> du projet
 * (F-38 / SF-38-05, décision D2).
 *
 * <p>Cas d'usage : ouvrir une session <b>Managed Agents</b> sur un projet en cible {@code RUNNER}.
 * Les Managed Agents exécutent les outils chez le fournisseur, hors de portée de tout reroutage : la
 * session travaillerait sur un bac à sable vide pendant que l'utilisateur croit qu'elle travaille
 * sur sa machine. Un refus lisible vaut mieux que ce malentendu.</p>
 */
public class ExecutionTargetModeException extends RuntimeException {

    public ExecutionTargetModeException(String message) {
        super(message);
    }
}
