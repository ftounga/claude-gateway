package fr.claudegateway.runner.teams;

import java.util.List;
import java.util.Locale;

/**
 * <b>La liste blanche des commandes de débogage</b> (F-87 / SF-87-02, étendue par F-108 / SF-108-01)
 * — et le verrou de sécurité du volet.
 *
 * <p>Le protocole de débogage d'un navigateur donne, entre autres, accès aux <b>cookies</b> et au
 * <b>stockage</b> de la session. Nous n'en voulons pas : l'avantage de l'approche navigateur est
 * précisément que les cookies et les jetons Microsoft <b>ne sont jamais rapatriés</b>. Cet avantage
 * ne se préserve pas par intention — il se préserve par une liste close, appliquée <b>avant</b>
 * émission, et par un test qui la garde.</p>
 *
 * <p><b>Ce que F-108 a changé, et pourquoi.</b> Jusqu'à F-88 le volet <i>lisait</i> : cinq commandes
 * suffisaient (connaître le navigateur, écouter le réseau, récupérer un corps, faire défiler). Le PO
 * a décidé le 2026-09-13 que le runner pouvait <b>agir</b> — naviguer, cliquer, taper, déposer,
 * télécharger — mais <b>uniquement sur les domaines Microsoft</b> (cadrage §4). La liste blanche
 * passe donc de cinq à la liste <b>strictement nécessaire</b> ; chacune des commandes d'action est,
 * en plus, gardée par une vérification de domaine <b>avant émission</b> ({@link MicrosoftDomains}) —
 * cette classe garde le <i>vocabulaire</i>, {@code PageActions} garde le <i>lieu</i>. Les refus
 * nommés cookies et stockage, eux, ne bougent pas : cette décision-là ne se rouvre pas.</p>
 */
public final class CdpCommands {

    // -------------------------------------------------------------- lecture (F-87 / SF-87-02)

    /** Connaître la version du navigateur — écrite dans le diagnostic de la sonde. */
    public static final String BROWSER_VERSION = "Browser.getVersion";

    /** Activer les événements de page (nécessaire pour que l'onglet parle). */
    public static final String PAGE_ENABLE = "Page.enable";

    /** Activer l'observation du réseau : c'est là que tout le volet se joue. */
    public static final String NETWORK_ENABLE = "Network.enable";

    /** Récupérer le corps d'une réponse déjà reçue par la page. */
    public static final String GET_RESPONSE_BODY = "Network.getResponseBody";

    /** Faire défiler, lire l'adresse courante, inspecter un champ : le DOM par le script. */
    public static final String EVALUATE = "Runtime.evaluate";

    // -------------------------------------------------------------- action (F-108 / SF-108-01)

    /** Naviguer l'onglet — gardé par domaine avant émission (§4.1). */
    public static final String PAGE_NAVIGATE = "Page.navigate";

    /** Cliquer — un événement de souris réel dans la page. */
    public static final String DISPATCH_MOUSE_EVENT = "Input.dispatchMouseEvent";

    /** Taper — un événement de clavier réel dans la page. */
    public static final String DISPATCH_KEY_EVENT = "Input.dispatchKeyEvent";

    /** Insérer du texte dans le champ actif — refusé sur un champ mot de passe (§4.3). */
    public static final String INSERT_TEXT = "Input.insertText";

    /** Racine du document — pour situer un champ de dépôt de fichier. */
    public static final String GET_DOCUMENT = "DOM.getDocument";

    /** Retrouver un élément par sélecteur — pour cibler un geste. */
    public static final String QUERY_SELECTOR = "DOM.querySelector";

    /** Poser les fichiers d'un champ de dépôt — l'unique façon de déposer sans boîte système. */
    public static final String SET_FILE_INPUT_FILES = "DOM.setFileInputFiles";

    /**
     * Diriger les téléchargements vers le dossier de travail du volet (§5.2) : c'est <b>Chrome</b>
     * qui télécharge, l'adresse signée ne passe jamais par notre code.
     */
    public static final String SET_DOWNLOAD_BEHAVIOR = "Browser.setDownloadBehavior";

    /** Attacher automatiquement les cadres et workers — filtré sur les mêmes domaines (§4.8). */
    public static final String SET_AUTO_ATTACH = "Target.setAutoAttach";

    private static final List<String> ALLOWED = List.of(
            BROWSER_VERSION, PAGE_ENABLE, NETWORK_ENABLE, GET_RESPONSE_BODY, EVALUATE,
            PAGE_NAVIGATE, DISPATCH_MOUSE_EVENT, DISPATCH_KEY_EVENT, INSERT_TEXT,
            GET_DOCUMENT, QUERY_SELECTOR, SET_FILE_INPUT_FILES, SET_DOWNLOAD_BEHAVIOR,
            SET_AUTO_ATTACH);

    /**
     * Ce qu'on refuse nommément. La liste blanche suffirait ; celle-ci existe pour que le refus
     * <b>dise</b> ce qui a été tenté, et pour qu'un futur contributeur lise noir sur blanc que ce
     * n'est pas un oubli.
     *
     * <p>La navigation et la saisie <b>ne sont plus</b> dans cette liste : elles sont désormais
     * permises, mais gardées par le domaine (voir {@code PageActions}). Les cookies et le stockage,
     * eux, y restent : c'est la décision de sécurité que le cadrage F-108 conserve explicitement.</p>
     */
    private static final List<String> NAMED_REFUSALS = List.of("network.getcookies",
            "network.getallcookies", "network.setcookie", "network.setcookies",
            "network.clearbrowsercookies", "storage.getcookies", "storage.setcookies",
            "storage.clearcookies", "storage.getstorageitems", "storage.setstorageitem");

    private CdpCommands() {
    }

    /** Vrai si la commande fait partie de la liste blanche. */
    public static boolean isAllowed(String method) {
        return method != null && ALLOWED.contains(method.strip());
    }

    /**
     * Garde d'émission : rien ne part sans passer par ici.
     *
     * @throws BrowserLinkException si la commande n'est pas dans la liste blanche
     */
    public static void assertAllowed(String method) {
        if (isAllowed(method)) {
            return;
        }
        String asked = method == null ? "" : method.strip().toLowerCase(Locale.ROOT);
        String why = NAMED_REFUSALS.contains(asked)
                ? " Cette commande toucherait aux cookies ou au stockage de la session :"
                        + " rien de ce qui authentifie l'utilisateur ne remonte, et ce garde-fou ne"
                        + " se rouvre pas."
                : "";
        throw new BrowserLinkException(BrowserLinkException.COMMAND_REFUSED,
                "Commande de débogage refusée : « " + (method == null ? "(aucune)" : method.strip())
                        + " »." + why + " Autorisées : " + String.join(", ", ALLOWED) + ".");
    }

    /** Les commandes autorisées, pour les dire à l'utilisateur qui veut savoir ce qu'on fait. */
    public static List<String> allowed() {
        return ALLOWED;
    }
}
