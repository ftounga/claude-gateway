package fr.claudegateway.runner.diag;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import fr.claudegateway.runner.teams.MicrosoftDomains;

/**
 * <b>L'expurgation à la source</b> des événements de diagnostic (F-132 / SF-132-01, cadrage §4) :
 * ce qui aide à diagnostiquer la plomberie, jamais ce que le poste contient.
 *
 * <p>Le runner tourne sur un poste <b>client/banque</b>. Un événement de diagnostic ne doit jamais
 * porter un secret, une URL brute (on ne remonte que sa <b>classe</b>), un chemin complet sensible
 * ou un contenu Teams. Cette classe est le garde-fou <b>en dernier ressort</b> : les appelants
 * expurgent déjà (ils passent une classe d'URL, un nombre, un état), mais tout message et tout champ
 * repassent ici avant d'entrer dans un {@link RunnerDiagEvent} — un oubli d'appelant ne peut pas
 * faire fuir un contenu.</p>
 *
 * <p>Esprit « relevé de forme » (SF-89-12 / {@code PayloadShape}) : on borne, on tronque, on ne
 * garde que des scalaires.</p>
 */
public final class RunnerDiagRedaction {

    /** Au-delà, un message est tronqué : un diagnostic est court par nature. */
    static final int MAX_MESSAGE_CHARS = 300;
    /** Au-delà, une valeur de champ texte est tronquée. */
    static final int MAX_FIELD_CHARS = 120;
    /** Au-delà, on refuse d'attacher trop de champs à un seul événement. */
    static final int MAX_FIELDS = 24;
    /** Au-delà, un code/catégorie est tronqué. */
    static final int MAX_LABEL_CHARS = 60;

    /** Toute URL (http/https/ws/wss/ftp/file) est remplacée par sa classe : jamais l'adresse brute. */
    private static final Pattern URL = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    private RunnerDiagRedaction() {
    }

    /**
     * Un message expurgé : contrôle-caractères ôtés, URL remplacées par leur classe, tronqué. Une
     * entrée nulle/vide rend {@code null} (le message est optionnel).
     */
    public static String message(String raw) {
        if (raw == null) {
            return null;
        }
        String value = CONTROL.matcher(raw).replaceAll(" ").strip();
        if (value.isEmpty()) {
            return null;
        }
        value = replaceUrls(value);
        if (value.length() > MAX_MESSAGE_CHARS) {
            value = value.substring(0, MAX_MESSAGE_CHARS) + "…";
        }
        return value;
    }

    /**
     * Une carte de champs expurgée : seuls les <b>scalaires</b> (nombre, booléen, texte court)
     * survivent. Un texte repasse par {@link #message} (URL → classe, tronqué). Toute valeur non
     * scalaire (carte, liste, objet) est <b>écartée</b> — elle pourrait cacher un contenu. Nombre de
     * champs borné.
     */
    public static Map<String, Object> fields(Map<String, ?> raw) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (raw == null) {
            return safe;
        }
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            if (safe.size() >= MAX_FIELDS) {
                break;
            }
            String key = label(entry.getKey());
            if (key == null) {
                continue;
            }
            Object scalar = scalar(entry.getValue());
            if (scalar != null) {
                safe.put(key, scalar);
            }
        }
        return safe;
    }

    /** Un code/catégorie borné à {@code [a-z0-9_]} minuscule et tronqué. Illisible → {@code null}. */
    public static String label(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        value = value.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > MAX_LABEL_CHARS ? value.substring(0, MAX_LABEL_CHARS) : value;
    }

    /**
     * La <b>classe</b> d'une URL — jamais l'URL brute (cadrage §4). Assez fine pour diagnostiquer
     * (page d'identification ? Teams ? SharePoint ? hors Microsoft ?), assez grossière pour ne rien
     * révéler du locataire (aucun sous-domaine, aucun chemin, aucune requête).
     */
    public static String urlClass(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "none";
        }
        if (MicrosoftDomains.isSignIn(rawUrl)) {
            return "sign_in";
        }
        String host = hostOf(rawUrl);
        if (host.isEmpty()) {
            return "unparseable";
        }
        if (host.contains("teams.")) {
            return "teams";
        }
        if (host.endsWith("sharepoint.com") || host.contains("onedrive")) {
            return "sharepoint";
        }
        if (MicrosoftDomains.isMicrosoftFamily(rawUrl)) {
            return "microsoft";
        }
        return "other";
    }

    private static String replaceUrls(String value) {
        return URL.matcher(value).replaceAll(match -> "<" + urlClass(match.group()) + ">");
    }

    /** Ne laisse passer qu'un scalaire. Un texte est expurgé et tronqué ; tout objet est écarté. */
    private static Object scalar(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof CharSequence cs) {
            String text = replaceUrls(CONTROL.matcher(cs.toString()).replaceAll(" ").strip());
            if (text.isEmpty()) {
                return null;
            }
            return text.length() > MAX_FIELD_CHARS ? text.substring(0, MAX_FIELD_CHARS) + "…" : text;
        }
        return null;
    }

    /** L'hôte d'une URL, minuscule, sans port ni chemin. Illisible → {@code ""}. */
    private static String hostOf(String url) {
        String value = url.strip().toLowerCase(Locale.ROOT);
        int schemeEnd = value.indexOf("://");
        if (schemeEnd >= 0) {
            value = value.substring(schemeEnd + 3);
        }
        int cut = indexOfAny(value);
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

    private static int indexOfAny(String value) {
        int best = -1;
        for (char c : new char[] {'/', '?', '#'}) {
            int idx = value.indexOf(c);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }
}
