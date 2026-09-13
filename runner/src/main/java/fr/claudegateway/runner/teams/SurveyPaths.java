package fr.claudegateway.runner.teams;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>Ce que le relevé a le droit d'écrire d'une adresse</b> (F-100 / SF-100-00) : un hôte ramené à
 * son <b>motif</b>, un chemin ramené à son <b>gabarit</b> — et rien d'autre.
 *
 * <p>Le relevé sert à dresser la table des chemins de Microsoft, qui sont <b>les mêmes pour tous les
 * clients</b> (correction du PO). Ce qui distingue un client — le nom de son tenant, l'identifiant
 * d'un fil, le nom d'un site, le titre d'un enregistrement — n'a donc rien à faire dans le rapport,
 * et n'y entre pas : la chaîne de requête et l'ancre sont retirées <b>à l'entrée</b>, et tout segment
 * qui ressemble à un identifiant devient {@code {id}}.</p>
 */
final class SurveyPaths {

    /** Le gabarit d'un segment qui porterait un identifiant ou un nom propre au client. */
    static final String ID = "{id}";

    /** Longueur maximale d'un chemin gabarisé : au-delà, on coupe et on le dit. */
    static final int MAX_PATH_CHARS = 300;

    /**
     * Segments après lesquels vient toujours un <b>nom</b> propre au client (site, équipe, personne,
     * lecteur, élément) : on ne l'écrit jamais, même s'il n'a pas l'air d'un identifiant.
     */
    private static final List<String> CONTAINERS = List.of("sites", "teams", "personal", "drives",
            "drive", "items", "users", "groups", "channels", "chats", "conversations", "threads",
            "meetings", "onlinemeetings", "recordings", "transcripts", "messages", "lists", "webs");

    private static final Pattern UUID_LIKE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX_LIKE = Pattern.compile("[0-9a-fA-F]{16,}");
    private static final Pattern ARGUMENT = Pattern.compile("\\([^)]*\\)?");
    private static final Pattern VERSION = Pattern.compile("v[0-9]+(\\.[0-9]+)?|beta");

    private SurveyPaths() {
    }

    /**
     * L'hôte, ramené à son motif. {@code contoso.sharepoint.com} devient {@code *.sharepoint.com},
     * {@code contoso-my.sharepoint.com} devient {@code *-my.sharepoint.com} : le nom du tenant n'est
     * jamais écrit, et le motif est ce que l'adaptateur reconnaît.
     */
    static String hostMotif(String url) {
        String host = MicrosoftDomains.hostOf(url);
        if (host.endsWith("-my.sharepoint.com")) {
            return "*-my.sharepoint.com";
        }
        if (host.endsWith(".sharepoint.com")) {
            return "*.sharepoint.com";
        }
        return host;
    }

    /** Le chemin gabarisé : sans requête, sans ancre, identifiants et noms remplacés par {@code {id}}. */
    static String template(String url) {
        // Les appels SharePoint portent leur argument entre parenthèses, parfois un chemin entier :
        // GetFileByServerRelativeUrl('/sites/Projet/Réunion.mp4'). L'argument disparaît avant découpe.
        String path = ARGUMENT.matcher(pathOf(ObservedResponse.withoutQuery(url))).replaceAll("(" + ID + ")");
        if (path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        StringBuilder out = new StringBuilder();
        String previous = "";
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            out.append('/').append(templateSegment(segment, previous));
            previous = segment.toLowerCase(Locale.ROOT);
        }
        String result = out.length() == 0 ? "/" : out.toString();
        return result.length() <= MAX_PATH_CHARS ? result
                : result.substring(0, MAX_PATH_CHARS) + "…";
    }

    private static String templateSegment(String segment, String previous) {
        if (CONTAINERS.contains(previous) && !CONTAINERS.contains(segment.toLowerCase(Locale.ROOT))
                && !VERSION.matcher(segment.toLowerCase(Locale.ROOT)).matches()) {
            return ID;
        }
        int paren = segment.indexOf('(');
        if (paren >= 0) {
            // Les appels SharePoint portent leur argument entre parenthèses : GetFileById('…').
            String head = segment.substring(0, paren);
            return (looksLikeId(head) ? ID : head) + "(" + ID + ")";
        }
        return looksLikeId(segment) ? ID : segment;
    }

    /** Vrai si un segment ressemble à un identifiant, un nom de fichier ou une valeur du client. */
    static boolean looksLikeId(String segment) {
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        String value = segment.strip();
        if (VERSION.matcher(value.toLowerCase(Locale.ROOT)).matches()) {
            return false;
        }
        for (char c : value.toCharArray()) {
            if (c == ':' || c == '@' || c == '%' || c == '=' || c == '!' || c == '~' || c == ','
                    || c == ' ' || c == '\'' || c > 127) {
                return true;
            }
        }
        if (UUID_LIKE.matcher(value).find() || HEX_LIKE.matcher(value).matches()) {
            return true;
        }
        long digits = value.chars().filter(Character::isDigit).count();
        if (digits >= 5) {
            return true;
        }
        boolean hasDigit = digits > 0;
        boolean hasUpper = value.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = value.chars().anyMatch(Character::isLowerCase);
        if (value.length() >= 24 && hasDigit) {
            return true;
        }
        // Un mélange de majuscules, minuscules et chiffres assez long : du base64, pas un mot.
        return value.length() >= 12 && hasDigit && hasUpper && hasLower;
    }

    /** Le chemin d'une adresse déjà privée de sa requête. */
    private static String pathOf(String url) {
        String value = url == null ? "" : url.strip();
        int scheme = value.indexOf("://");
        int start = scheme < 0 ? 0 : value.indexOf('/', scheme + 3);
        if (start < 0) {
            return "";
        }
        return value.substring(start);
    }
}
