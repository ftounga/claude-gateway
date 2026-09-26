package fr.claudegateway.atelier;

import java.util.Locale;

/**
 * <b>Les noms et les chemins qui viennent du modèle</b> (F-129 / SF-129-07).
 *
 * <p>Deux gestes, écrits <b>une seule fois</b> : refuser un chemin qui sortirait du projet, et
 * nettoyer un nom de fichier avant de le déposer. Ils existaient dans la construction de deck
 * (SF-129-05) ; le document et le classeur en ont exactement besoin. Deux copies du même refus
 * finiraient par diverger, et c'est sur ce genre d'écart qu'une isolation se perd.</p>
 *
 * <p><b>Ce que ça ne fait pas</b> : ni lecture, ni écriture. La lecture et le dépôt restent
 * {@link ProjectFileRead} et {@link ProjectFileDeposit}, sous l'isolation du tour.</p>
 */
public final class ProjectFileNames {

    /** Longueur maximale du nom déposé — au-delà, un nom ne se lit plus dans un projet. */
    public static final int MAX_NAME = 60;

    private ProjectFileNames() {
    }

    /**
     * Le refus d'un chemin venu du modèle, ou {@code null} s'il est acceptable : relatif, sans
     * remontée de dossier, jamais une lettre de lecteur Windows.
     *
     * @param what ce que désigne le chemin, pour que le refus soit <b>dit</b> (« d'image »…)
     */
    public static String refusePath(String path, String what) {
        String value = path == null ? "" : path;
        if (value.isEmpty()) {
            return "Chemin " + what + " vide.";
        }
        if (value.startsWith("/") || value.startsWith("\\") || value.matches("^[A-Za-z]:.*")) {
            return "Chemin " + what + " absolu refusé (« " + value + " ») : donne un chemin du projet.";
        }
        if (value.contains("..")) {
            return "Chemin " + what + " hors du projet refusé (« " + value + " »).";
        }
        return null;
    }

    /**
     * Le nom du fichier déposé : dérivé du nom demandé ou, à défaut, du titre — <b>jamais un
     * chemin</b>, toujours l'extension attendue.
     *
     * @param extension l'extension imposée, sans point (« pptx », « docx », « xlsx »)
     * @param fallback  le nom de repli quand il ne reste rien de lisible
     */
    public static String clean(String raw, String title, String extension, String fallback) {
        String base = raw == null || raw.isBlank() ? title : raw;
        base = base == null ? "" : base.strip();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String suffix = "." + extension.toLowerCase(Locale.ROOT);
        if (base.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            base = base.substring(0, base.length() - suffix.length());
        }
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else if (c == ' ' || c == '.' || c == '\'') {
                sb.append('-');
            }
            if (sb.length() >= MAX_NAME) {
                break;
            }
        }
        String cleaned = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return (cleaned.isEmpty() ? fallback : cleaned) + suffix;
    }
}
