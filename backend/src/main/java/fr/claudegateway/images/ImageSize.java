package fr.claudegateway.images;

import java.util.Locale;

/**
 * Les tailles d'image autorisées (F-142 / SF-142-04) — <b>liste blanche</b>. Une taille hors liste
 * retombe sur {@link #SQUARE} (défaut), jamais une valeur libre relayée au fournisseur.
 */
public enum ImageSize {

    /** Carré (défaut) — couverture, vignette. */
    SQUARE("1024x1024"),
    /** Paysage — bandeau, couverture large. */
    LANDSCAPE("1536x1024"),
    /** Portrait — affiche, page haute. */
    PORTRAIT("1024x1536");

    private final String api;

    ImageSize(String api) {
        this.api = api;
    }

    /** La valeur envoyée au fournisseur (ex. {@code 1024x1024}). */
    public String api() {
        return api;
    }

    /** La taille demandée, ou {@link #SQUARE} par défaut (valeur vide, inconnue ou hors liste). */
    public static ImageSize fromRequest(String raw) {
        if (raw == null || raw.isBlank()) {
            return SQUARE;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        for (ImageSize size : values()) {
            if (size.api.equals(value)) {
                return size;
            }
        }
        return SQUARE;
    }
}
