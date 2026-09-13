package fr.claudegateway.radar;

import java.util.Locale;

/**
 * Normalisations du Radar (F-99 / SF-99-01). Aucune dépendance, aucun état : c'est ce qui permet de
 * les tester sans base.
 */
public final class RadarText {

    /** Marque de coupure d'une citation trop longue. */
    static final String ELLIPSIS = "…";

    private RadarText() {
    }

    /**
     * Texte obligatoire : rogné, non vide, borné.
     *
     * @throws InvalidRadarInputException s'il est vide ou trop long
     */
    public static String required(String value, int max, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidRadarInputException("Le champ « " + field + " » est requis.");
        }
        if (trimmed.length() > max) {
            throw new InvalidRadarInputException(
                    "Le champ « " + field + " » dépasse " + max + " caractères.");
        }
        return trimmed;
    }

    /**
     * Texte facultatif : rogné, {@code null} s'il est vide.
     *
     * @throws InvalidRadarInputException s'il est trop long
     */
    public static String optional(String value, int max, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new InvalidRadarInputException(
                    "Le champ « " + field + " » dépasse " + max + " caractères.");
        }
        return trimmed;
    }

    /**
     * Citation courte (cadrage §4.6) : rognée, non vide, et <b>tronquée</b> au-delà de la borne plutôt
     * que refusée — une synchro ne doit pas échouer sur une phrase longue.
     */
    public static String quote(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidRadarInputException("La citation est requise.");
        }
        int max = RadarEvidence.MAX_QUOTE_LENGTH;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + ELLIPSIS;
    }

    /** Lien profond : rogné ; ignoré (et non tronqué) s'il est vide ou trop long — un lien coupé ment. */
    public static String deepLink(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > RadarEvidence.MAX_DEEP_LINK_LENGTH) {
            return null;
        }
        return trimmed;
    }

    /** Clé de comparaison : rognée, espaces réduits, minuscules. */
    public static String key(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
