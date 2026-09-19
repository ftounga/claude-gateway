package fr.claudegateway.runner.diag;

import java.util.List;
import java.util.Locale;

/**
 * Niveau d'un événement de diagnostic du runner (F-132 / SF-132-02), miroir côté gateway du niveau
 * émis par le runner (SF-132-01). L'ordre déclaré est l'ordre de sévérité : {@code DEBUG} &lt;
 * {@code INFO} &lt; {@code WARN} &lt; {@code ERROR}.
 */
public enum RunnerDiagLevel {

    DEBUG,
    INFO,
    WARN,
    ERROR;

    /** Niveau par défaut si le runner n'en déclare pas un valide (défaut confirmé PO). */
    public static final RunnerDiagLevel DEFAULT = INFO;

    /**
     * Le niveau nommé, insensible à la casse ; une valeur inconnue ou nulle rend {@code null} (au
     * filtre de lecture, un nom illisible ne filtre rien ; à l'ingestion, on retombe sur {@link #DEFAULT}).
     */
    public static RunnerDiagLevel parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Les noms des niveaux qui atteignent (ou dépassent) ce seuil — pour un filtre {@code level in (...)}. */
    public List<String> namesAtLeast() {
        return List.of(values()).stream()
                .filter(l -> l.ordinal() >= this.ordinal())
                .map(Enum::name)
                .toList();
    }
}
