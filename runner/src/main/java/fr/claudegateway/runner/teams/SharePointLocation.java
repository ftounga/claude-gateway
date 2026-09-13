package fr.claudegateway.runner.teams;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Un emplacement de fichiers SharePoint / OneDrive</b>, lu dans une adresse web
 * (F-108 / SF-108-03).
 *
 * <p>Toutes les capacités fichiers de F-108 parlent l'API REST SharePoint, qui désigne un dossier ou
 * un fichier par son <b>chemin relatif serveur</b> ({@code /sites/ProjetIAM/Shared Documents/General})
 * et s'adresse à un <b>site</b> ({@code https://contoso.sharepoint.com/sites/ProjetIAM}). Ce que
 * l'agent et l'utilisateur ont en main, eux, ce sont des <b>adresses web</b> : lien de bibliothèque,
 * lien de pièce jointe observé dans Teams, lien {@code AllItems.aspx?id=…}. Cette classe fait la
 * traduction, et <b>refuse en nommant pourquoi</b> ce qu'elle ne sait pas traduire — jamais un
 * emplacement deviné.</p>
 *
 * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel</b> : les formes d'adresse
 * reconnues ici sont celles de la documentation publique SharePoint Online (sites {@code /sites/},
 * {@code /teams/}, OneDrive {@code /personal/} sur l'hôte {@code -my.sharepoint.com}, liens directs
 * {@code /:x:/r/}).</p>
 *
 * @param origin     {@code https://hôte}, en minuscule
 * @param sitePath   chemin du site ({@code /sites/ProjetIAM}), {@code ""} pour le site racine
 * @param serverPath chemin relatif serveur décodé, sans barre finale
 */
public record SharePointLocation(String origin, String sitePath, String serverPath) {

    /** Préfixes de collection de sites reconnus : un segment de nom les suit. */
    private static final List<String> SITE_PREFIXES = List.of("sites", "teams", "personal");

    public SharePointLocation {
        origin = origin == null ? "" : origin;
        sitePath = sitePath == null ? "" : sitePath;
        serverPath = serverPath == null ? "" : serverPath;
    }

    /**
     * Analyse une adresse web.
     *
     * @return l'emplacement, ou un refus nommé
     */
    public static Parsed parse(String raw) {
        String url = raw == null ? "" : raw.strip();
        if (url.isEmpty()) {
            return Parsed.refused("aucune adresse donnée");
        }
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return Parsed.refused("l'adresse doit commencer par https://");
        }
        if (MicrosoftDomains.isSignIn(url)) {
            return Parsed.refused("c'est une page d'identification Microsoft, pas un emplacement");
        }
        String host = MicrosoftDomains.hostOf(url);
        if (!MicrosoftDomains.isAllowed(url)) {
            return Parsed.refused("« " + host + " » n'est pas un domaine Microsoft autorisé");
        }
        if ("onedrive.live.com".equals(host)) {
            return Parsed.refused("OneDrive grand public (onedrive.live.com) n'est pas pris en "
                    + "charge : seuls OneDrive professionnel et SharePoint le sont");
        }
        if (!host.endsWith(".sharepoint.com")) {
            return Parsed.refused("« " + host + " » n'héberge pas de fichiers SharePoint ou OneDrive");
        }

        String afterScheme = url.substring("https://".length());
        int slash = afterScheme.indexOf('/');
        String pathAndQuery = slash < 0 ? "/" : afterScheme.substring(slash);
        String query = "";
        int hash = pathAndQuery.indexOf('#');
        if (hash >= 0) {
            pathAndQuery = pathAndQuery.substring(0, hash);
        }
        int question = pathAndQuery.indexOf('?');
        if (question >= 0) {
            query = pathAndQuery.substring(question + 1);
            pathAndQuery = pathAndQuery.substring(0, question);
        }
        String path = decode(pathAndQuery);

        // Liens directs « /:f:/r/… » : le « r » dit que la suite est le chemin réel. Les autres
        // (« /:w:/s/… », « /:x:/g/… ») sont des liens de partage opaques : ils ne portent pas de
        // chemin, et on ne va pas les suivre pour le découvrir.
        if (path.startsWith("/:")) {
            String[] parts = path.split("/", 4);
            if (parts.length >= 4 && "r".equals(parts[2])) {
                path = "/" + parts[3];
            } else {
                return Parsed.refused("lien de partage opaque : donnez l'adresse du dossier ou du "
                        + "fichier (celle de la barre d'adresse de la bibliothèque)");
            }
        }

        // « …/Forms/AllItems.aspx?id=/sites/x/Shared Documents/General » et la vue OneDrive
        // « …/_layouts/15/onedrive.aspx?id=… » : le chemin est dans « id ».
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".aspx")) {
            String id = queryParam(query, "id");
            if (id.isEmpty()) {
                return Parsed.refused("page SharePoint sans chemin de dossier (paramètre « id » "
                        + "absent) : ouvrez le dossier voulu et donnez son adresse");
            }
            path = id;
            lower = path.toLowerCase(Locale.ROOT);
        }
        if (lower.contains("/_layouts/") || lower.contains("/_api/") || lower.contains("/_vti_bin/")) {
            return Parsed.refused("adresse technique SharePoint, pas un emplacement de fichiers");
        }

        String cleaned = clean(path);
        List<String> segments = segments(cleaned);
        if (segments.stream().anyMatch(s -> ".".equals(s) || "..".equals(s))) {
            return Parsed.refused("chemin relatif (« . » ou « .. ») refusé");
        }
        String sitePath = "";
        int libraryIndex = 0;
        if (segments.size() >= 2 && SITE_PREFIXES.contains(segments.get(0).toLowerCase(Locale.ROOT))) {
            sitePath = "/" + segments.get(0) + "/" + segments.get(1);
            libraryIndex = 2;
        }
        if (segments.size() <= libraryIndex) {
            return Parsed.refused("l'adresse désigne un site, pas une bibliothèque ni un dossier : "
                    + "ajoutez la bibliothèque (par exemple « Shared Documents »)");
        }
        return Parsed.of(new SharePointLocation("https://" + host, sitePath, cleaned));
    }

    /** L'adresse du site, base de tous les appels d'API. */
    public String siteUrl() {
        return origin + sitePath;
    }

    /** Le nom de l'élément (dernier segment). */
    public String name() {
        List<String> segments = segments(serverPath);
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
    }

    /** Le dossier parent, ou l'emplacement lui-même s'il est la bibliothèque. */
    public SharePointLocation parent() {
        int cut = serverPath.lastIndexOf('/');
        String candidate = cut <= 0 ? serverPath : serverPath.substring(0, cut);
        if (candidate.length() <= sitePath.length()) {
            return this;
        }
        return new SharePointLocation(origin, sitePath, candidate);
    }

    /** Un élément de ce dossier. */
    public SharePointLocation child(String name) {
        String leaf = name == null ? "" : name.strip();
        return new SharePointLocation(origin, sitePath, serverPath + "/" + leaf);
    }

    /** Le chemin sous le site : bibliothèque puis dossiers. */
    public List<String> underSite() {
        return segments(serverPath.substring(Math.min(sitePath.length(), serverPath.length())));
    }

    /** Vrai pour un OneDrive professionnel ({@code -my.sharepoint.com/personal/…}). */
    public boolean isOneDrive() {
        return origin.endsWith("-my.sharepoint.com") && sitePath.toLowerCase(Locale.ROOT)
                .startsWith("/personal/");
    }

    /**
     * Le libellé en clair : « ProjetIAM › Shared Documents › General », « OneDrive › Documents ›
     * Livrables ». Jamais un identifiant technique.
     */
    public String label() {
        List<String> parts = new ArrayList<>();
        if (isOneDrive()) {
            parts.add("OneDrive");
        } else if (!sitePath.isEmpty()) {
            parts.add(segments(sitePath).get(1));
        } else {
            parts.add(MicrosoftDomains.hostOf(origin));
        }
        parts.addAll(underSite());
        return String.join(" › ", parts);
    }

    /** L'adresse web de l'élément, chemin encodé. */
    public String webUrl() {
        return origin + encodePath(serverPath);
    }

    /**
     * L'adresse de téléchargement : {@code download.aspx?SourceUrl=}. <b>Construite ici, non
     * signée</b> : c'est la session du navigateur qui l'autorise, et c'est Chrome qui télécharge.
     */
    public String downloadUrl() {
        return siteUrl() + "/_layouts/15/download.aspx?SourceUrl="
                + URLEncoder.encode(serverPath, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Vrai si l'adresse donnée est sur le même hôte que cet emplacement. */
    public boolean sameOrigin(String url) {
        return MicrosoftDomains.hostOf(url).equals(MicrosoftDomains.hostOf(origin));
    }

    // ------------------------------------------------------------------ plomberie

    static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : segments(path)) {
            out.append('/').append(URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    private static String clean(String path) {
        String value = path.replace('\\', '/');
        while (value.contains("//")) {
            value = value.replace("//", "/");
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equalsIgnoreCase(name)) {
                return decode(pair.substring(eq + 1));
            }
        }
        return "";
    }

    /** Le résultat d'une analyse : un emplacement, ou la raison du refus. */
    public record Parsed(SharePointLocation location, String refusal) {

        static Parsed of(SharePointLocation location) {
            return new Parsed(location, "");
        }

        static Parsed refused(String why) {
            return new Parsed(null, why);
        }

        public boolean ok() {
            return location != null;
        }
    }
}
