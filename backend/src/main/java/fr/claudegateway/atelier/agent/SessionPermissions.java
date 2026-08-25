package fr.claudegateway.atelier.agent;

/**
 * Politique d'autorisation des outils d'une session (F-33 / SF-33-01), exprimée <b>dans le
 * domaine</b> : le service dit ce qu'il veut (« demander avant d'exécuter une commande »), le
 * provider seul sait comment le fournisseur l'exprime (Provider Independence).
 *
 * @param askBeforeShellCommands vrai si l'agent doit <b>demander l'autorisation</b> avant d'exécuter
 *                               une commande shell ; faux pour le comportement historique, où tout
 *                               s'exécute sans demander
 */
public record SessionPermissions(boolean askBeforeShellCommands) {

    /** Politique historique : l'agent exécute tout sans demander (comportement d'avant F-33). */
    public static final SessionPermissions ALLOW_ALL = new SessionPermissions(false);

    /** Politique de validation : l'agent demande avant chaque commande shell. */
    public static final SessionPermissions ASK_BEFORE_SHELL = new SessionPermissions(true);

    /** Politique correspondant à l'option portée par le projet. */
    public static SessionPermissions of(boolean askBeforeShellCommands) {
        return askBeforeShellCommands ? ASK_BEFORE_SHELL : ALLOW_ALL;
    }
}
