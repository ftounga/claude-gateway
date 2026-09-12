package fr.claudegateway.teams.block;

/**
 * <b>Un bloc refusé</b> (F-89 / SF-89-02), et le motif que l'agent reçoit pour se corriger.
 *
 * <p>Ce n'est pas une panne : c'est la règle du volet appliquée à la lettre — <i>échouer
 * bruyamment, jamais à moitié faux</i>. Un bloc auquel il manque une source, une fenêtre de lecture
 * ou la déclaration de ses manques n'est pas un bloc incomplet qu'on afficherait quand même : c'est
 * un compte rendu plausible et faux, sur lequel quelqu'un déciderait.</p>
 *
 * <p>Le message est écrit <b>pour le modèle</b> : il dit ce qui manque et ce qu'il faut faire, pas
 * « invalid input ». C'est ce qui permet à un tour de se corriger tout seul plutôt que de rendre la
 * main à l'utilisateur.</p>
 */
public class TeamsBlockRejectedException extends RuntimeException {

    public TeamsBlockRejectedException(String message) {
        super(message);
    }
}
