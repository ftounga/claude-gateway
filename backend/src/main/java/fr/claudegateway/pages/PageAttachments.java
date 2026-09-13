package fr.claudegateway.pages;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <b>Ce qu'une pièce jointe peut être</b> (F-109 / SF-109-01) : un nom plat, et un type parmi une liste
 * close. Le type est déduit de l'extension, jamais du contenu ni d'une déclaration du modèle : c'est lui
 * qui est servi, et {@code nosniff} interdit au navigateur d'en décider un autre.
 */
public final class PageAttachments {

    /** Longueur maximale d'un nom de pièce jointe. */
    public static final int MAX_NAME_CHARS = 100;

    /** Un nom plat : ni dossier, ni {@code ..}, ni caractère qui changerait le sens d'une URL. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("csv", "text/csv; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("woff2", "font/woff2"));

    private PageAttachments() {
    }

    /** Vrai si le nom est plat, borné, sans {@code ..}, et d'une extension de la liste. */
    public static boolean isValidName(String name) {
        return name != null && name.length() <= MAX_NAME_CHARS && NAME.matcher(name).matches()
                && !name.contains("..") && contentType(name).isPresent();
    }

    /** Le type servi pour ce nom, ou vide si l'extension n'est pas de la liste. */
    public static Optional<String> contentType(String name) {
        if (name == null) {
            return Optional.empty();
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }

    /** Les extensions acceptées, pour le message de refus. */
    public static String acceptedExtensions() {
        return String.join(", ", new java.util.TreeSet<>(TYPES.keySet()));
    }
}
