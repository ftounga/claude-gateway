package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.List;

/**
 * Le chemin d'un fichier apporté par un paquet (F-51 / SF-51-01) : <b>relatif au projet</b>, et rien
 * d'autre.
 *
 * <p><b>Pourquoi cette classe existe.</b> Un paquet est rédigé par l'admin et <b>appliqué sur la
 * machine d'un utilisateur</b>. Le chemin qu'il porte est donc la seule donnée du paquet qui décide
 * <i>où</i> quelque chose sera écrit chez quelqu'un d'autre. Il est validé à la
 * <b>publication</b> — le plus tôt possible, une fois, avant que le contenu n'existe pour
 * quiconque — plutôt qu'au moment du dépôt, où un refus arriverait chez l'utilisateur pour une faute
 * qu'il n'a pas commise.</p>
 *
 * <p>Sont refusés : le chemin vide, la racine ({@code /...}), la lettre de lecteur Windows
 * ({@code C:\...}), toute traversée ({@code ..}), et tout ce qui se réduit à rien
 * ({@code ./././}). Les séparateurs sont normalisés et les segments {@code .} supprimés, si bien que
 * le chemin rendu est <b>canonique</b> : deux écritures du même chemin se comparent.</p>
 */
public final class GovernancePath {

    /** Longueur maximale d'un chemin, alignée sur la colonne qui le stocke. */
    public static final int MAX_LENGTH = 255;

    private GovernancePath() {
    }

    /**
     * Forme canonique d'un chemin de paquet, ou {@code null} s'il est refusé.
     *
     * @param raw le chemin tel qu'il a été saisi
     * @return le chemin relatif normalisé ({@code a/b/c.md}), ou {@code null} si inutilisable
     */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.replace('\\', '/').trim();
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            return null;
        }
        // Lettre de lecteur Windows : `C:/x` n'est pas relatif, même après normalisation des
        // séparateurs. Le motif est étroit à dessein — un `:` ailleurs dans un nom de fichier est
        // légal sur les systèmes POSIX et n'a pas à être refusé ici.
        if (normalized.length() >= 2 && normalized.charAt(1) == ':'
                && Character.isLetter(normalized.charAt(0))) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                return null; // Traversée : un paquet n'écrit jamais hors du projet.
            }
            parts.add(segment);
        }
        if (parts.isEmpty()) {
            return null;
        }
        String result = String.join("/", parts);
        return result.length() > MAX_LENGTH ? null : result;
    }
}
