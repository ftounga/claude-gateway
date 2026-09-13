package fr.claudegateway.runner.teams;

/**
 * Un rattachement au navigateur qui n'a pas abouti (F-87 / SF-87-02).
 *
 * <p><b>Le message porte toujours le remède ET le moyen de l'appliquer.</b> C'est la leçon écrite le
 * 2026-09-12 à propos de F-80 : nommer le remède sans donner le moyen est inutilisable. Dire « il
 * faut lancer Chrome avec le port de débogage » n'aide personne ; donner la ligne de commande
 * exacte, pour ce système-ci, avec le dossier de profil, aide tout le monde.</p>
 *
 * @see BrowserLaunchAdvice
 */
public final class BrowserLinkException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Rien n'écoute sur le port de débogage. */
    public static final String BROWSER_NOT_DETECTED = "browser_not_detected";

    /** Le navigateur répond, mais aucun onglet Teams n'est ouvert. */
    public static final String TEAMS_NOT_OPEN = "teams_not_open";

    /** L'onglet Teams existe, mais la session n'est pas ouverte. */
    public static final String NOT_SIGNED_IN = "not_signed_in";

    /** Le port visé n'est pas sur la boucle locale : on ne se rattache jamais à distance. */
    public static final String REMOTE_BROWSER_REFUSED = "remote_browser_refused";

    /** La liaison a été perdue pendant le travail. */
    public static final String LINK_LOST = "link_lost";

    /** Une commande de débogage hors liste blanche a été demandée. */
    public static final String COMMAND_REFUSED = "command_refused";

    /** Un geste d'action a été tenté hors des domaines Microsoft autorisés (F-108, §4.1). */
    public static final String DOMAIN_REFUSED = "domain_refused";

    /** Un geste d'action a été tenté sur une page d'identification Microsoft (F-108, §4.2). */
    public static final String SIGN_IN_REFUSED = "sign_in_refused";

    /** Une saisie a été tentée dans un champ de type mot de passe (F-108, §4.3). */
    public static final String PASSWORD_FIELD_REFUSED = "password_field_refused";

    private final String code;

    public BrowserLinkException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BrowserLinkException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Code stable, pour l'indicateur de liaison (SF-87-03). Jamais montré tel quel. */
    public String code() {
        return code;
    }
}
