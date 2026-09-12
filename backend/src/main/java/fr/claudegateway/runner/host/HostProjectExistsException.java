package fr.claudegateway.runner.host;

/**
 * Ouverture d'un projet <b>refusée</b> parce qu'un projet de l'appelant occupe déjà ce dossier sous
 * ce poste (F-72 / SF-72-01).
 *
 * <p>Un refus, et non une reprise silencieuse du projet existant : rendre {@code 200} sur un
 * double-clic masquerait le doublon sous une apparence de succès, et l'écran ne saurait pas s'il
 * vient de créer quelque chose. C'est précisément le défaut que F-72 supprime — deux entités du
 * même nom, et un utilisateur perdu.</p>
 *
 * <p>Le message <b>nomme</b> le projet déjà ouvert : l'utilisateur sait alors où aller, au lieu de
 * chercher ce qui lui est refusé.</p>
 */
public class HostProjectExistsException extends RuntimeException {

    public HostProjectExistsException(String existingProjectName, String path) {
        super(message(existingProjectName, path));
    }

    private static String message(String existingProjectName, String path) {
        String where = path == null || path.isEmpty() ? "la racine de ce poste" : "« " + path + " »";
        return "Ce dossier est déjà ouvert : " + where + " porte le projet « " + existingProjectName
                + " ». Ouvrez-le depuis la carte du poste plutôt que d'en créer un second.";
    }
}
