package fr.claudegateway.runner.host;

/**
 * Suppression d'un poste <b>refusée</b> parce qu'il porte encore des projets (F-69 / SF-69-01).
 *
 * <p>Décision du PO : <b>pas de cascade</b>. Une cascade effacerait des conversations que
 * l'utilisateur ne voyait même plus ; le refus l'oblige à regarder ce qu'il jette. Elle remplace
 * aussi le détachement silencieux d'avant F-69, qui laissait des projets sans machine sans que
 * personne l'ait demandé — une troisième voie que personne n'avait choisie.</p>
 *
 * <p>Le message est <b>actionnable</b> : il dit combien de projets restent et où les trouver. Il ne
 * les nomme pas — lister des noms dans un message d'erreur ferait de l'erreur une vue.</p>
 */
public class HostHasProjectsException extends RuntimeException {

    private final int remainingProjects;

    public HostHasProjectsException(String hostName, int remainingProjects) {
        super(message(hostName, remainingProjects));
        this.remainingProjects = remainingProjects;
    }

    /** Nombre de projets encore rattachés au poste — repris tel quel par l'écran. */
    public int getRemainingProjects() {
        return remainingProjects;
    }

    private static String message(String hostName, int remaining) {
        String projets = remaining == 1 ? "1 projet" : remaining + " projets";
        return "« " + hostName + " » porte encore " + projets
                + ". Supprimez-les depuis la liste des projets de la Forge, puis supprimez le poste."
                + " Rien n'est effacé sur votre machine.";
    }
}
