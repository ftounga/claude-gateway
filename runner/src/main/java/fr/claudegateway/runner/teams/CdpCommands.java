package fr.claudegateway.runner.teams;

import java.util.List;
import java.util.Locale;

/**
 * <b>La liste blanche des commandes de débogage</b> (F-87 / SF-87-02) — et le verrou de sécurité du
 * volet.
 *
 * <p>Le protocole de débogage d'un navigateur donne, entre autres, accès aux <b>cookies</b> et au
 * <b>stockage</b> de la session. Nous n'en voulons pas : l'avantage de l'approche navigateur est
 * précisément que les cookies et les jetons Microsoft <b>ne sont jamais rapatriés</b>. Cet avantage
 * ne se préserve pas par intention — il se préserve par une liste close, appliquée <b>avant</b>
 * émission, et par un test qui la garde.</p>
 *
 * <p>Les cinq commandes autorisées suffisent à tout ce que fait le volet : connaître le navigateur,
 * écouter le réseau, récupérer le corps d'une réponse, et faire défiler la page.</p>
 */
public final class CdpCommands {

    /** Connaître la version du navigateur — écrite dans le diagnostic de la sonde. */
    public static final String BROWSER_VERSION = "Browser.getVersion";

    /** Activer les événements de page (nécessaire pour que l'onglet parle). */
    public static final String PAGE_ENABLE = "Page.enable";

    /** Activer l'observation du réseau : c'est là que tout le volet se joue. */
    public static final String NETWORK_ENABLE = "Network.enable";

    /** Récupérer le corps d'une réponse déjà reçue par la page. */
    public static final String GET_RESPONSE_BODY = "Network.getResponseBody";

    /** Le seul geste demandé au DOM : faire défiler. */
    public static final String EVALUATE = "Runtime.evaluate";

    private static final List<String> ALLOWED = List.of(BROWSER_VERSION, PAGE_ENABLE,
            NETWORK_ENABLE, GET_RESPONSE_BODY, EVALUATE);

    /**
     * Ce qu'on refuse nommément. La liste blanche suffirait ; celle-ci existe pour que le refus
     * <b>dise</b> ce qui a été tenté, et pour qu'un futur contributeur lise noir sur blanc que ce
     * n'est pas un oubli.
     */
    private static final List<String> NAMED_REFUSALS = List.of("network.getcookies",
            "network.getallcookies", "network.setcookie", "network.setcookies",
            "network.clearbrowsercookies", "storage.getcookies", "storage.setcookies",
            "page.navigate", "input.dispatchkeyevent", "input.dispatchmouseevent");

    private CdpCommands() {
    }

    /** Vrai si la commande fait partie des cinq autorisées. */
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
                ? " Cette commande toucherait aux cookies, au stockage ou à la conduite de la page :"
                        + " le volet Teams lit, il ne pilote pas, et rien de ce qui authentifie la"
                        + " session ne remonte."
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
