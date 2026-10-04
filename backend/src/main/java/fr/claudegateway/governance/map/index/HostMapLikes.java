package fr.claudegateway.governance.map.index;

import java.util.Collection;
import java.util.Locale;

/**
 * Les motifs {@code LIKE} de l'index (F-174) : jokers échappés, une seule convention
 * ({@code ESCAPE '\'}), et le format de la colonne d'identifiants.
 */
public final class HostMapLikes {

    private HostMapLikes() {
    }

    /** Échappe {@code \}, {@code %} et {@code _} : un identifiant est une valeur, pas un motif. */
    public static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Égalité exacte d'un identifiant dans la colonne {@code identifiers} (« \nv1\nv2\n »). */
    public static String exactIdentifier(String identifier) {
        return "%\n" + escape(identifier.toLowerCase(Locale.ROOT)) + "\n%";
    }

    /** Le texte contient ce terme (déjà en minuscules). */
    public static String contains(String term) {
        return "%" + escape(term.toLowerCase(Locale.ROOT)) + "%";
    }

    /** Sérialise des identifiants pour la colonne : en minuscules, encadrés de sauts de ligne. */
    public static String identifiersColumn(Collection<String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return null;
        }
        StringBuilder column = new StringBuilder("\n");
        for (String identifier : identifiers) {
            if (identifier == null || identifier.isBlank() || identifier.indexOf('\n') >= 0) {
                continue;
            }
            column.append(identifier.strip().toLowerCase(Locale.ROOT)).append('\n');
        }
        return column.length() == 1 ? null : column.toString();
    }
}
