package fr.claudegateway.runner.diag;

import java.util.Locale;

/**
 * Niveau d'un événement de diagnostic du runner (F-132 / SF-132-01).
 *
 * <p>L'ordre déclaré <b>est</b> l'ordre de sévérité : {@link #DEBUG} &lt; {@link #INFO} &lt;
 * {@link #WARN} &lt; {@link #ERROR}. Le collecteur {@link RunnerDiag} ne retient un événement que si
 * son niveau atteint le seuil courant — {@code INFO} par défaut, le {@code DEBUG} n'étant activé
 * ponctuellement que pour un diagnostic (SF-132-05).</p>
 */
public enum RunnerDiagLevel {

    DEBUG,
    INFO,
    WARN,
    ERROR;

    /** Vrai si ce niveau atteint (ou dépasse) le seuil donné. */
    public boolean reaches(RunnerDiagLevel threshold) {
        return threshold != null && this.ordinal() >= threshold.ordinal();
    }

    /**
     * Le niveau nommé, insensible à la casse ; une valeur inconnue ou nulle rend {@code null} — au
     * réglage du seuil (SF-132-05), un nom illisible ne doit pas passer pour un niveau valide.
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
}
