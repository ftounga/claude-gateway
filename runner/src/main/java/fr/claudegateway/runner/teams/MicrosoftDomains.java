package fr.claudegateway.runner.teams;

import java.util.List;
import java.util.Locale;

/**
 * <b>La liste close des domaines Microsoft</b> (F-108 / SF-108-01, cadrage §4.1 et §4.2) — et le
 * verrou qui décide où le runner a le droit d'<b>agir</b>.
 *
 * <p>F-87 avait ouvert l'<i>observation</i> d'un seul onglet, celui de Teams. F-108 ouvre les
 * <i>gestes</i> — naviguer, cliquer, taper, déposer, télécharger — mais la décision de sécurité du
 * PO les borne à une liste <b>close</b> : hors de ces domaines, aucun geste ne part. Cette classe
 * porte cette liste, et une seule : tout le reste du volet la consulte, personne ne la recopie.</p>
 *
 * <p><b>Deux questions, deux réponses.</b> « Cette adresse est-elle un domaine Microsoft autorisé ? »
 * ({@link #isAllowed(String)}) garde les gestes. « Cette adresse est-elle une page d'identification ? »
 * ({@link #isSignIn(String)}) les interdit encore plus franchement : même sur un domaine Microsoft,
 * on ne clique ni ne tape sur une page de connexion (§4.2). L'ordre importe — une page
 * d'identification est écartée <b>avant</b> d'être jugée autorisée.</p>
 */
public final class MicrosoftDomains {

    /**
     * Les domaines où agir est permis (§4.1). Un préfixe {@code *.} vaut « ce domaine et tous ses
     * sous-domaines » ; sans joker, l'hôte doit correspondre exactement.
     */
    private static final List<String> ALLOWED = List.of(
            "teams.microsoft.com",
            "teams.cloud.microsoft",
            "teams.live.com",
            "*.sharepoint.com",
            "onedrive.live.com",
            "*.office.com",
            "*.officeapps.live.com",
            "*.cloud.microsoft");

    /**
     * Les hôtes d'identification (§4.2) : aucun geste, jamais. Ils ne sont volontairement pas dans
     * {@link #ALLOWED} — mais comme {@code *.cloud.microsoft} pourrait un jour en couvrir un, la
     * garde les écarte <b>explicitement</b>, avant tout autre jugement.
     */
    private static final List<String> SIGN_IN = List.of(
            "login.microsoftonline.com",
            "login.live.com",
            "login.microsoft.com");

    private MicrosoftDomains() {
    }

    /**
     * Vrai si un geste a le droit de partir sur cette adresse : un domaine Microsoft de la liste
     * close, et <b>pas</b> une page d'identification. Une adresse illisible, vide ou hors liste rend
     * faux — le refus est le défaut, jamais l'exception.
     */
    public static boolean isAllowed(String url) {
        String host = hostOf(url);
        if (host.isEmpty() || matches(host, SIGN_IN)) {
            return false;
        }
        return matches(host, ALLOWED);
    }

    /** Vrai si l'adresse est une page d'identification Microsoft (§4.2). */
    public static boolean isSignIn(String url) {
        return matches(hostOf(url), SIGN_IN);
    }

    /** Les domaines autorisés, pour les dire à qui veut savoir ce que le runner s'autorise. */
    public static List<String> allowed() {
        return ALLOWED;
    }

    /** L'hôte d'une URL, en minuscule et sans le port. Une entrée illisible rend {@code ""}. */
    static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        String value = url.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        int schemeEnd = value.indexOf("://");
        if (schemeEnd >= 0) {
            value = value.substring(schemeEnd + 3);
        }
        // Retire tout ce qui suit l'hôte : chemin, requête, ancre, et les identifiants @ éventuels.
        int cut = indexOfAny(value, '/', '?', '#');
        if (cut >= 0) {
            value = value.substring(0, cut);
        }
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value;
    }

    private static boolean matches(String host, List<String> patterns) {
        if (host.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1); // « .sharepoint.com »
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (host.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfAny(String value, char a, char b, char c) {
        int best = -1;
        for (char ch : new char[] {a, b, c}) {
            int index = value.indexOf(ch);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }
}
